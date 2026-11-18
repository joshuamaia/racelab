package dev.racelab.scenario.bank;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountJpaRepository extends JpaRepository<AccountEntity, Long> {

    // @snippet:start bank-withdrawal PESSIMISTIC_LOCKING java "Spring Data repository with PESSIMISTIC_WRITE"
    @Lock(LockModeType.PESSIMISTIC_WRITE) // row lock taken by the SELECT (FOR UPDATE family), held until commit
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") Long id);
    // @snippet:end
}
