package com.grid07.social_api.repository;

import com.grid07.social_api.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PostRepository extends JpaRepository<Post, Long> {}