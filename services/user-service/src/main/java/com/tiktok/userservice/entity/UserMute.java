package com.tiktok.userservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_mutes")
public class UserMute extends BaseEntity {

    @Column(name = "muter_id", nullable = false)
    private Long muterId;

    @Column(name = "muted_id", nullable = false)
    private Long mutedId;
}
