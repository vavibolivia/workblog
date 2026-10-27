package com.workblog.reaction;

public final class ReactionDtos {
    private ReactionDtos() {}

    /** 토글 결과: 내가 공감한 상태인지와 글의 공감 수. */
    public record LikeResponse(boolean liked, long count) {}
}
