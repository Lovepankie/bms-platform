package com.rincoltech.bms.lending.collateral.internal;

import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CollateralResponse;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.Event;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.Valuation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the collateral register. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class CollateralRepository {

    /** FR-COL-02: the latest valuation's forced sale value, else the item's estimate. */
    private static final String SELECT = """
            SELECT c.id, c.branch_id, c.member_id, c.collateral_type, c.description, c.reference_no,
                   c.reference_no_normalised, c.owner_name, c.owner_relationship, c.estimated_value_minor, c.currency,
                   c.custody_status, c.storage_location, c.created_at, c.updated_at, c.version,
                   coalesce((SELECT v.forced_sale_value_minor FROM lending_collateral_valuations v
                              WHERE v.collateral_id = c.id AND v.forced_sale_value_minor IS NOT NULL
                              ORDER BY v.valued_on DESC, v.created_at DESC LIMIT 1),
                            c.estimated_value_minor) AS collateral_value_minor
              FROM lending_collateral_items c
            """;

    private final JdbcClient jdbc;

    CollateralRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CollateralResponse> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = ?")
                .param(id)
                .query(CollateralRepository::map)
                .optional();
    }

    Optional<CollateralResponse> lock(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = ? FOR UPDATE OF c")
                .param(id)
                .query(CollateralRepository::map)
                .optional();
    }

    /**
     * One page ordered by creation. {@code branchIds} is already intersected with the principal's
     * scope; {@code null} means every branch.
     */
    List<CollateralResponse> page(
            List<UUID> branchIds,
            UUID memberId,
            List<String> types,
            List<String> statuses,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND c.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (memberId != null) {
            sql.append(" AND c.member_id = :memberId");
            params.put("memberId", memberId);
        }
        if (!types.isEmpty()) {
            sql.append(" AND c.collateral_type IN (:types)");
            params.put("types", types);
        }
        if (!statuses.isEmpty()) {
            sql.append(" AND c.custody_status IN (:statuses)");
            params.put("statuses", statuses);
        }
        if (afterCreated != null) {
            sql.append(" AND (c.created_at, c.id) > (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY c.created_at, c.id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query(CollateralRepository::map)
                .list();
    }

    /**
     * FR-COL-01 (interim until loans exist): another item of the same type and normalised
     * reference that is still pledged, in custody or seized.
     */
    Optional<UUID> activeDuplicate(String type, String referenceNormalised, UUID excludeId) {
        return jdbc.sql("""
                        SELECT id FROM lending_collateral_items
                         WHERE collateral_type = ? AND reference_no_normalised = ?
                           AND custody_status NOT IN ('released', 'disposed') AND id IS DISTINCT FROM ?::uuid
                         LIMIT 1
                        """)
                .params(type, referenceNormalised, excludeId)
                .query(UUID.class)
                .optional();
    }

    void insert(CollateralResponse c, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO lending_collateral_items (id, tenant_id, branch_id, member_id, collateral_type,
                            description, reference_no, reference_no_normalised, owner_name, owner_relationship,
                            estimated_value_minor, currency, custody_status, storage_location, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branchId, :memberId, :collateralType,
                            :description, :referenceNo, :referenceNoNormalised, :ownerName, :ownerRelationship,
                            :estimatedValueMinor, :currency, :custodyStatus, :storageLocation, :createdBy)
                        """)
                .param("id", c.id())
                .param("branchId", c.branchId())
                .param("memberId", c.memberId())
                .param("collateralType", c.collateralType())
                .param("description", c.description())
                .param("referenceNo", c.referenceNo())
                .param("referenceNoNormalised", c.referenceNoNormalised())
                .param("ownerName", c.ownerName())
                .param("ownerRelationship", c.ownerRelationship())
                .param("estimatedValueMinor", c.estimatedValueMinor())
                .param("currency", c.currency())
                .param("custodyStatus", c.custodyStatus())
                .param("storageLocation", c.storageLocation())
                .param("createdBy", createdBy)
                .update();
    }

    void update(CollateralResponse c) {
        jdbc.sql("""
                        UPDATE lending_collateral_items SET description = :description, reference_no = :referenceNo,
                            reference_no_normalised = :referenceNoNormalised, owner_name = :ownerName,
                            owner_relationship = :ownerRelationship, estimated_value_minor = :estimatedValueMinor,
                            custody_status = :custodyStatus, storage_location = :storageLocation,
                            updated_at = now(), version = version + 1
                        WHERE id = :id
                        """).paramSource(c).update();
    }

    void insertValuation(UUID id, UUID collateralId, CollateralApi.ValuationRequest v, UUID recordedBy) {
        jdbc.sql("""
                        INSERT INTO lending_collateral_valuations (id, tenant_id, collateral_id, valued_on, valuer_name,
                            market_value_minor, forced_sale_value_minor, note, recorded_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        collateralId,
                        v.valuedOn(),
                        v.valuerName(),
                        v.marketValueMinor(),
                        v.forcedSaleValueMinor(),
                        v.note(),
                        recordedBy)
                .update();
    }

    void insertEvent(UUID collateralId, Event e) {
        jdbc.sql("""
                        INSERT INTO lending_collateral_events (id, tenant_id, collateral_id, event_type, from_status,
                            to_status, location, counterparty_name, note, occurred_at, recorded_by, approval_request_id)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        e.id(),
                        collateralId,
                        e.eventType(),
                        e.fromStatus(),
                        e.toStatus(),
                        e.location(),
                        e.counterpartyName(),
                        e.note(),
                        Timestamp.from(e.occurredAt()),
                        e.recordedBy(),
                        e.approvalRequestId())
                .update();
    }

    List<Valuation> valuations(UUID collateralId) {
        return jdbc.sql("""
                        SELECT id, valued_on, valuer_name, market_value_minor, forced_sale_value_minor, note, recorded_by,
                               created_at
                          FROM lending_collateral_valuations WHERE collateral_id = ?
                         ORDER BY valued_on DESC, created_at DESC
                        """)
                .param(collateralId)
                .query((rs, n) -> new Valuation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("valued_on", LocalDate.class),
                        rs.getString("valuer_name"),
                        rs.getLong("market_value_minor"),
                        rs.getObject("forced_sale_value_minor", Long.class),
                        rs.getString("note"),
                        rs.getObject("recorded_by", UUID.class),
                        instant(rs.getTimestamp("created_at"))))
                .list();
    }

    List<Event> events(UUID collateralId) {
        return jdbc.sql("""
                        SELECT id, event_type, from_status, to_status, location, counterparty_name, note, occurred_at,
                               recorded_by, approval_request_id
                          FROM lending_collateral_events WHERE collateral_id = ?
                         ORDER BY occurred_at, created_at, id
                        """)
                .param(collateralId)
                .query((rs, n) -> new Event(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("location"),
                        rs.getString("counterparty_name"),
                        rs.getString("note"),
                        instant(rs.getTimestamp("occurred_at")),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getObject("approval_request_id", UUID.class)))
                .list();
    }

    void linkDocument(UUID collateralId, UUID documentId) {
        jdbc.sql("""
                        INSERT INTO lending_collateral_documents (tenant_id, collateral_id, document_id)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?)
                        """).params(collateralId, documentId).update();
    }

    List<UUID> documentIds(UUID collateralId) {
        return jdbc.sql(
                        "SELECT document_id FROM lending_collateral_documents WHERE collateral_id = ? ORDER BY created_at")
                .param(collateralId)
                .query(UUID.class)
                .list();
    }

    private static CollateralResponse map(ResultSet rs, int n) throws SQLException {
        return new CollateralResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("member_id", UUID.class),
                rs.getString("collateral_type"),
                rs.getString("description"),
                rs.getString("reference_no"),
                rs.getString("reference_no_normalised"),
                rs.getString("owner_name"),
                rs.getString("owner_relationship"),
                rs.getObject("estimated_value_minor", Long.class),
                rs.getString("currency"),
                rs.getString("custody_status"),
                rs.getString("storage_location"),
                rs.getObject("collateral_value_minor", Long.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getInt("version"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
