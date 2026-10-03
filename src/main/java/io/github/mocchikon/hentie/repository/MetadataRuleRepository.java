package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.MetadataRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Every lookup is by {@code (type, source_name_lower)} or {@code (type, target_id)}, the two indexes. */
public interface MetadataRuleRepository extends JpaRepository<MetadataRule, Integer>
{
    Optional<MetadataRule> findByTypeAndSourceNameLower(MetadataType type, String sourceNameLower);

    List<MetadataRule> findByTypeAndSourceNameLowerIn(MetadataType type, Collection<String> sourceNameLower);

    Page<MetadataRule> findByType(MetadataType type, Pageable pageable);

    /** After a merge, so a rule keeps pointing at a row that exists. */
    @Modifying
    @Query("update MetadataRule r set r.targetId = :target where r.type = :type and r.targetId = :source")
    int retarget(@Param("type") MetadataType type, @Param("source") Integer source, @Param("target") Integer target);

    /** After the target is removed, its rules become blocking ones rather than write a dangling id. */
    @Modifying
    @Query("update MetadataRule r set r.targetId = null where r.type = :type and r.targetId = :target")
    int clearTarget(@Param("type") MetadataType type, @Param("target") Integer target);
}
