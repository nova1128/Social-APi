package com.grid07.social_api.repository;

import com.grid07.social_api.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CommentRepository extends JpaRepository<Comment, Long> {}