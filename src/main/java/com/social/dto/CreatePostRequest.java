package com.social.dto;

import lombok.Data;


@Data
public class CreatePostRequest {
    private String content;
    private Long userId;
    private Long botId;
}