package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Table("chat_share_dedupe")
public class ChatShareDedupe {
    @PrimaryKey
    private ChatShareDedupeKey key;
    @Column("share_id")
    private Long shareId;
}
