package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.lending.members.internal.NextOfKinApi.NextOfKinResponse;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.RelatedMember;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for {@code lending_next_of_kin} and the relationship view. As for members, row-level
 * security supplies the tenant predicate and inserts take {@code tenant_id} from the bound tenant.
 */
@Repository
class NextOfKinRepository {

    private static final String SELECT = """
            SELECT k.id, k.member_id, k.full_name, k.phone_e164, k.national_id, k.relationship, k.relationship_text,
                   k.location, k.is_primary, k.linked_member_id, lm.member_no AS linked_member_no, k.link_method,
                   k.link_status, k.created_at, k.updated_at, k.version
              FROM lending_next_of_kin k LEFT JOIN lending_members lm ON lm.id = k.linked_member_id
            """;

    private final JdbcClient jdbc;

    NextOfKinRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Where a next of kin points: a member, how the link was found, and its status (FR-MEM-07). */
    record Link(UUID memberId, String method, String status) {

        static final Link NONE = new Link(null, null, "none");
    }

    List<NextOfKinResponse> forMember(UUID memberId) {
        return jdbc.sql(SELECT + " WHERE k.member_id = ? ORDER BY k.is_primary DESC, k.created_at, k.id")
                .param(memberId)
                .query(NextOfKinRepository::map)
                .list();
    }

    Optional<NextOfKinResponse> find(UUID kinId) {
        return jdbc.sql(SELECT + " WHERE k.id = ?")
                .param(kinId)
                .query(NextOfKinRepository::map)
                .optional();
    }

    Optional<NextOfKinResponse> lock(UUID kinId) {
        return jdbc.sql(SELECT + " WHERE k.id = ? FOR UPDATE OF k")
                .param(kinId)
                .query(NextOfKinRepository::map)
                .optional();
    }

    long countForMember(UUID memberId) {
        return jdbc.sql("SELECT count(*) FROM lending_next_of_kin WHERE member_id = ?")
                .param(memberId)
                .query(Long.class)
                .single();
    }

    void insert(NextOfKinResponse k, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO lending_next_of_kin (id, tenant_id, member_id, full_name, phone_e164, national_id,
                            relationship, relationship_text, location, is_primary, linked_member_id, link_method,
                            link_status, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :memberId, :fullName, :phone, :nin,
                            :relationship, :relationshipText, :location, :isPrimary, :linkedMemberId, :linkMethod,
                            :linkStatus, :createdBy)
                        """)
                .param("id", k.id())
                .param("memberId", k.memberId())
                .param("fullName", k.fullName())
                .param("phone", k.phoneE164())
                .param("nin", k.nationalId())
                .param("relationship", k.relationship())
                .param("relationshipText", k.relationshipText())
                .param("location", k.location())
                .param("isPrimary", k.isPrimary())
                .param("linkedMemberId", k.linkedMemberId())
                .param("linkMethod", k.linkMethod())
                .param("linkStatus", k.linkStatus())
                .param("createdBy", createdBy)
                .update();
    }

    void update(NextOfKinResponse k) {
        jdbc.sql("""
                        UPDATE lending_next_of_kin SET full_name = :fullName, phone_e164 = :phoneE164,
                            national_id = :nationalId, relationship = :relationship,
                            relationship_text = :relationshipText, location = :location, is_primary = :isPrimary,
                            linked_member_id = :linkedMemberId, link_method = :linkMethod, link_status = :linkStatus,
                            updated_at = now(), version = version + 1
                        WHERE id = :id
                        """).paramSource(k).update();
    }

    void delete(UUID kinId) {
        jdbc.sql("DELETE FROM lending_next_of_kin WHERE id = ?").param(kinId).update();
    }

    /** Clears the member's other primary flag first, so the one-primary index never trips. */
    void clearPrimary(UUID memberId, UUID exceptKinId) {
        jdbc.sql("""
                        UPDATE lending_next_of_kin SET is_primary = false, updated_at = now(), version = version + 1
                        WHERE member_id = ? AND is_primary AND id IS DISTINCT FROM ?::uuid
                        """).params(memberId, exceptKinId).update();
    }

    /**
     * FR-MEM-07, forward: a NIN match links automatically; failing that, a phone that belongs to
     * exactly one other member is suggested for a user to confirm.
     */
    Link resolve(UUID ownerMemberId, String nin, String phone) {
        if (nin != null) {
            Optional<UUID> byNin = jdbc.sql("SELECT id FROM lending_members WHERE national_id = ? AND id <> ?")
                    .params(nin, ownerMemberId)
                    .query(UUID.class)
                    .optional();
            if (byNin.isPresent()) {
                return new Link(byNin.get(), "nin", "confirmed");
            }
        }
        if (phone != null) {
            List<UUID> byPhone = jdbc.sql("SELECT id FROM lending_members WHERE phone_e164 = ? AND id <> ? LIMIT 2")
                    .params(phone, ownerMemberId)
                    .query(UUID.class)
                    .list();
            if (byPhone.size() == 1) {
                return new Link(byPhone.getFirst(), "phone", "suggested");
            }
        }
        return Link.NONE;
    }

    /**
     * FR-MEM-07, backward: when a member is registered or their NIN or phone changes, next of kin
     * that other members named and that match are linked (NIN, replacing any phone suggestion)
     * or suggested (phone, only where nothing is linked yet). Returns the kin ids touched.
     */
    List<UUID> linkToMember(UUID memberId, String nin, String phone) {
        List<UUID> byNin = nin == null
                ? List.of()
                : jdbc.sql("""
                                UPDATE lending_next_of_kin SET linked_member_id = :member, link_method = 'nin',
                                    link_status = 'confirmed', updated_at = now(), version = version + 1
                                WHERE national_id = :nin AND member_id <> :member
                                  AND (linked_member_id IS NULL OR link_method = 'phone')
                                RETURNING id
                                """)
                        .param("member", memberId)
                        .param("nin", nin)
                        .query(UUID.class)
                        .list();
        List<UUID> byPhone = phone == null
                ? List.of()
                : jdbc.sql("""
                                UPDATE lending_next_of_kin SET linked_member_id = :member, link_method = 'phone',
                                    link_status = 'suggested', updated_at = now(), version = version + 1
                                WHERE phone_e164 = :phone AND member_id <> :member AND linked_member_id IS NULL
                                RETURNING id
                                """)
                        .param("member", memberId)
                        .param("phone", phone)
                        .query(UUID.class)
                        .list();
        return Stream.concat(byNin.stream(), byPhone.stream()).toList();
    }

    /** Members who name {@code memberId} as next of kin, through the relationship view (FR-MEM-08). */
    record NamedBy(UUID id, UUID branchId, String memberNo, String fullName, String relationship) {

        RelatedMember visible(boolean inScope) {
            return inScope
                    ? new RelatedMember(id, memberNo, fullName, relationship, true)
                    : new RelatedMember(null, memberNo, null, null, false);
        }
    }

    List<NamedBy> namedAsKinBy(UUID memberId) {
        return jdbc.sql("""
                        SELECT m.id, m.branch_id, m.member_no, m.full_name, k.relationship
                          FROM lending_member_links_v v
                          JOIN lending_members m ON m.id = v.related_member_id
                          JOIN lending_next_of_kin k ON k.id = v.source_id
                         WHERE v.member_id = ? AND v.link_kind = 'named_as_kin_by'
                         ORDER BY m.member_no
                        """)
                .param(memberId)
                .query((rs, n) -> new NamedBy(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("member_no"),
                        rs.getString("full_name"),
                        rs.getString("relationship")))
                .list();
    }

    private static NextOfKinResponse map(ResultSet rs, int n) throws SQLException {
        return new NextOfKinResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("member_id", UUID.class),
                rs.getString("full_name"),
                rs.getString("phone_e164"),
                rs.getString("national_id"),
                rs.getString("relationship"),
                rs.getString("relationship_text"),
                rs.getString("location"),
                rs.getBoolean("is_primary"),
                rs.getObject("linked_member_id", UUID.class),
                rs.getString("linked_member_no"),
                rs.getString("link_method"),
                rs.getString("link_status"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getInt("version"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
