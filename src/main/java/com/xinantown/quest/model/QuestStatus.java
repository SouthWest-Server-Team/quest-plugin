package com.xinantown.quest.model;

public enum QuestStatus {
    OPEN,       // 等待接取
    ACCEPTED,   // 已接取，执行中
    SUBMITTED,  // 接收方已提交，等待确认
    COMPLETED,  // 完成
    CANCELLED   // 已取消
}
