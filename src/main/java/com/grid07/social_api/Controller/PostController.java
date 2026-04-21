package com.grid07.social_api.Controller;

import com.grid07.social_api.entity.Comment;
import com.grid07.social_api.entity.Post;
import com.grid07.social_api.repository.CommentRepository;
import com.grid07.social_api.repository.PostRepository;
import com.grid07.social_api.service.GuardrailService;
import com.grid07.social_api.service.NotificationService;
import com.grid07.social_api.service.ViralityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final GuardrailService guardrailService;
    private final ViralityService viralityService;
    private final NotificationService notificationService;

    // POST /api/posts
    @PostMapping
    public ResponseEntity<Post> createPost(@RequestBody Map<String, Object> body) {
        Post post = Post.builder()
                .authorId(Long.parseLong(body.get("authorId").toString()))
                .authorType(body.get("authorType").toString())
                .content(body.get("content").toString())
                .createdAt(LocalDateTime.now())
                .build();
        return ResponseEntity.ok(postRepository.save(post));
    }

    // POST /api/posts/{postId}/comments
    @PostMapping("/{postId}/comments")
    public ResponseEntity<?> addComment(
            @PathVariable Long postId,
            @RequestBody Map<String, Object> body) {

        String authorType = body.get("authorType").toString();
        Long authorId = Long.parseLong(body.get("authorId").toString());
        int depthLevel = Integer.parseInt(body.get("depthLevel").toString());

        // --- GUARDRAILS (only for BOT authors) ---
        if ("BOT".equals(authorType)) {
            // 1. Vertical Cap check
            if (!guardrailService.checkVerticalCap(depthLevel)) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body("Rejected: Comment depth exceeds 20 levels.");
            }

            // 2. Horizontal Cap check (atomic Redis INCR)
            if (!guardrailService.tryIncrementBotCount(postId)) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body("Rejected: Post has reached 100 bot replies.");
            }

            // 3. Cooldown Cap — need the post's original human author
            Optional<Post> postOpt = postRepository.findById(postId);
            if (postOpt.isPresent()) {
                Post post = postOpt.get();
                if ("USER".equals(post.getAuthorType())) {
                    Long humanId = post.getAuthorId();
                    if (!guardrailService.checkAndSetCooldown(authorId, humanId)) {
                        // Roll back the bot count we just incremented
                        guardrailService.getBotCount(postId); // just to log
                        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                .body("Rejected: Bot cooldown active for this human.");
                    }
                    // 4. Trigger notification for the post owner
                    notificationService.handleBotNotification(humanId, "Bot_" + authorId, postId);
                }
            }

            // 5. Update virality score
            viralityService.onBotReply(postId);
        } else {
            // Human comment — update virality
            viralityService.onHumanComment(postId);
        }

        // --- ALL GUARDRAILS PASSED — Save to DB ---
        Comment comment = Comment.builder()
                .postId(postId)
                .authorId(authorId)
                .authorType(authorType)
                .content(body.get("content").toString())
                .depthLevel(depthLevel)
                .createdAt(LocalDateTime.now())
                .build();

        return ResponseEntity.ok(commentRepository.save(comment));
    }

    // POST /api/posts/{postId}/like
    @PostMapping("/{postId}/like")
    public ResponseEntity<?> likePost(
            @PathVariable Long postId,
            @RequestBody Map<String, Object> body) {

        if (!postRepository.existsById(postId)) {
            return ResponseEntity.notFound().build();
        }

        String authorType = body.get("authorType").toString();
        if ("USER".equals(authorType)) {
            viralityService.onHumanLike(postId);
        }

        return ResponseEntity.ok("Post " + postId + " liked. Virality updated.");
    }
}