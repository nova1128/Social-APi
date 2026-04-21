package com.grid07.social_api.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Comment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long postId;
    private Long authorId;
    private String authorType; // "USER" or "BOT"
    private String content;
    private int depthLevel;
    private LocalDateTime createdAt;
}