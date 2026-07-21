package com.automation.repository;

import com.automation.entity.AuditFindingTodo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditFindingTodoRepository extends JpaRepository<AuditFindingTodo, String> {
    List<AuditFindingTodo> findByFindingId(String findingId);
    List<AuditFindingTodo> findByTodoId(String todoId);
    List<AuditFindingTodo> findByFindingIdIn(List<String> findingIds);
    Optional<AuditFindingTodo> findByFindingIdAndTodoId(String findingId, String todoId);
}
