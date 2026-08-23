package com.runninggu.server.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String nickname;

    @Column(name = "nickname_key", nullable = false, unique = true, length = 12)
    private String nicknameKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {}

    public static AppUser create(
            String nickname,
            String nicknameKey,
            Instant createdAt) {
        AppUser user = new AppUser();
        user.nickname = nickname;
        user.nicknameKey = nicknameKey;
        user.createdAt = createdAt;
        user.updatedAt = createdAt;
        return user;
    }

    public void changeNickname(
            String nickname,
            String nicknameKey,
            Instant changedAt) {
        this.nickname = nickname;
        this.nicknameKey = nicknameKey;
        this.updatedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
