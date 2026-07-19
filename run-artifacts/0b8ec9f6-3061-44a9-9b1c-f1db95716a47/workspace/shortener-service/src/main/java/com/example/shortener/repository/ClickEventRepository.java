package com.example.shortener.repository;

import com.example.shortener.model.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
    List<ClickEvent> findByCode(String code);
    long countByCode(String code);
}
