package com.runninggu.server.favorite.domain;

import com.runninggu.server.auth.domain.AppUser;
import com.runninggu.server.contest.domain.Contest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** 사용자가 찜한 canonical 대회 관계다. (SPEC 결정-16, API 명세 §7-C) */
@Entity
@Table(
        name = "favorite",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_favorite_user_contest",
                columnNames = {"user_id", "contest_id"}))
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Favorite() {}

    public Long getId() {
        return id;
    }

    public Contest getContest() {
        return contest;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
