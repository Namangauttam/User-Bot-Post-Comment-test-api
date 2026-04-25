package com.social.dto;

import lombok.Data;


@Data
public class CreateCommentRequest {
    private String content;
    private Long userId;
    private Long botId;
    private Integer depthLevel;
    private Long parentCommentId;
}