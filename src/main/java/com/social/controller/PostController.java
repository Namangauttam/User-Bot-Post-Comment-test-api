package com.social.controller;

import com.social.dto.*;
import com.social.entity.*;
import com.social.service.PostService;
import com.social.service.ViralityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService     postService;
    private final ViralityService viralityService;


    @PostMapping
    public ResponseEntity<Post> createPost(@RequestBody CreatePostRequest req) {
        Post post = postService.createPost(req);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(post);
    }


    @PostMapping("/{postId}/comments")
    public ResponseEntity<Comment> addComment(
            @PathVariable Long postId,
            @RequestBody CreateCommentRequest req) {

        Comment comment = postService.addComment(postId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(comment);
    }


    @PostMapping("/{postId}/like")
    public ResponseEntity<Post> likePost(
            @PathVariable Long postId,
            @RequestBody LikePostRequest req) {

        Post post = postService.likePost(postId, req.getUserId());
        return ResponseEntity.ok(post);   // 200 OK
    }


    @GetMapping("/{postId}/virality")
    public ResponseEntity<Long> getViralityScore(@PathVariable Long postId) {
        Long score = viralityService.getViralityScore(postId);
        return ResponseEntity.ok(score);
    }
}