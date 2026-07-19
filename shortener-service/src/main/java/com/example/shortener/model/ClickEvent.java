package com.example.shortener.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "click_event")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 12)
    private String code;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt;

    protected ClickEvent() {
        // JPA
    }

    public ClickEvent(String code, Instant clickedAt) {
        this.code = code;
        this.clickedAt = clickedAt;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public Instant getClickedAt() { return clickedAt; }
}
