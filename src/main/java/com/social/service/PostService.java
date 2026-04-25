package com.social.service;

import com.social.dto.CreateCommentRequest;
import com.social.dto.CreatePostRequest;
import com.social.entity.*;
import com.social.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.social.exception.HorizontalCapException;
import com.social.exception.VerticalCapException;
import com.social.exception.CooldownCapException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {


    private final PostRepository       postRepository;
    private final CommentRepository    commentRepository;
    private final UserRepository       userRepository;
    private final BotRepository        botRepository;
    private final ViralityService      viralityService;
    private final NotificationService  notificationService;


    @Transactional
    public Post createPost(CreatePostRequest req) {
        Post post = new Post();
        post.setContent(req.getContent());

        if (req.getUserId() != null) {

            User user = userRepository.findById(req.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found: " + req.getUserId()));
            post.setUserAuthor(user);

        } else if (req.getBotId() != null) {

            Bot bot = botRepository.findById(req.getBotId())
                    .orElseThrow(() -> new RuntimeException("Bot not found: " + req.getBotId()));
            post.setBotAuthor(bot);

        } else {
            throw new RuntimeException("Request must have either userId or botId");
        }

        Post saved = postRepository.save(post);
        log.info("Post created with id: {}", saved.getId());
        return saved;
    }


    @Transactional
    public Comment addComment(Long postId, CreateCommentRequest req) {


        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

        boolean isBotCommenting = req.getBotId() != null;


        if (isBotCommenting) {


            if (!viralityService.tryIncrementBotCount(postId)) {

                throw new HorizontalCapException(
                        "Post " + postId + " has reached the 100 bot reply limit."
                );
            }


            int depth = req.getDepthLevel() != null ? req.getDepthLevel() : 0;
            if (!viralityService.isDepthAllowed(depth)) {

                viralityService.decrementBotCount(postId);
                throw new VerticalCapException(
                        "Comment depth " + depth + " exceeds maximum of 20."
                );
            }


            if (post.getUserAuthor() != null) {
                Long humanId = post.getUserAuthor().getId();
                Long botId   = req.getBotId();

                if (!viralityService.tryAcquireCooldown(botId, humanId)) {

                    viralityService.decrementBotCount(postId);
                    throw new CooldownCapException(
                            "Bot " + botId + " is on cooldown for user " + humanId
                    );
                }


                viralityService.addBotReply(postId);

                Bot bot = botRepository.findById(botId)
                        .orElseThrow(() -> new RuntimeException("Bot not found: " + botId));

                notificationService.handleBotInteraction(
                        humanId, bot.getName(), "post #" + postId
                );
            }
        } else {

            viralityService.addHumanComment(postId);
        }


        Comment comment = new Comment();
        comment.setPost(post);
        comment.setContent(req.getContent());
        comment.setDepthLevel(req.getDepthLevel() != null ? req.getDepthLevel() : 0);

        if (isBotCommenting) {
            Bot bot = botRepository.findById(req.getBotId())
                    .orElseThrow(() -> new RuntimeException("Bot not found: " + req.getBotId()));
            comment.setBotAuthor(bot);
        } else {
            User user = userRepository.findById(req.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found: " + req.getUserId()));
            comment.setUserAuthor(user);
        }


        if (req.getParentCommentId() != null) {
            Comment parent = commentRepository.findById(req.getParentCommentId())
                    .orElseThrow(() -> new RuntimeException("Parent comment not found"));
            comment.setParentComment(parent);
        }

        Comment saved = commentRepository.save(comment);
        log.info("Comment saved with id: {}", saved.getId());
        return saved;
    }



    @Transactional
    public Post likePost(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found: " + postId));


        post.setLikeCount(post.getLikeCount() + 1);
        postRepository.save(post);


        viralityService.addHumanLike(postId);

        log.info("Post {} liked by user {}. Total likes: {}", postId, userId, post.getLikeCount());
        return post;
    }


}