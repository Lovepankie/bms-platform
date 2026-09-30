package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.kernel.Masking;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberListItem;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for {@code lending_members}. No query carries a tenant predicate: row-level security adds
 * it in the database (ADR-003). Inserts take {@code tenant_id} from the transaction's bound
 * tenant ({@code current_setting('app.tenant_id')}), so no tenant id ever passes through Java on
 * the write path, and the policy's WITH CHECK would refuse any other value anyway.
 */
@Repository
class MemberRepository {

    private static final String COLUMNS = """
            id, branch_id, member_no, full_name, first_name, last_name, phone_e164, alt_phone_e164, id_type,
            national_id, other_id_number, date_of_birth, gender, marital_status, district, sub_county, village,
            location, occupation, other_income_source, monthly_income_minor, currency, kyc_status, kyc_verified_by,
            kyc_verified_at, status, is_blacklisted, blacklist_reason, officer_user_id, source, created_at, updated_at,
            version
            """;

    /** Trigram similarity at or above which a name counts as a likely duplicate (FR-MEM-04). */
    static final double NAME_SIMILARITY = 0.6;

    private final JdbcClient jdbc;

    MemberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record NewMember(
            UUID id,
            UUID branchId,
            String memberNo,
            String fullName,
            String firstName,
            String lastName,
            String phoneE164,
            String altPhoneE164,
            String idType,
            String nationalId,
            String otherIdNumber,
            LocalDate dateOfBirth,
            String gender,
            String maritalStatus,
            String district,
            String subCounty,
            String village,
            String location,
            String occupation,
            String otherIncomeSource,
            Long monthlyIncomeMinor,
            String currency,
            UUID officerUserId,
            UUID createdBy) {}

    void insert(NewMember m) {
        jdbc.sql("""
                        INSERT INTO lending_members (
                            id, tenant_id, branch_id, member_no, full_name, first_name, last_name, phone_e164,
                            alt_phone_e164, id_type, national_id, other_id_number, date_of_birth, gender, marital_status,
                            district, sub_county, village, location, occupation, other_income_source,
                            monthly_income_minor, currency, kyc_status, status, officer_user_id, source, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branchId, :memberNo, :fullName, :firstName, :lastName, :phoneE164,
                            :altPhoneE164, :idType, :nationalId, :otherIdNumber, :dateOfBirth, :gender, :maritalStatus,
                            :district, :subCounty, :village, :location, :occupation, :otherIncomeSource,
                            :monthlyIncomeMinor, :currency, 'incomplete', 'active', :officerUserId, 'staff', :createdBy)
                        """).paramSource(m).update();
    }

    Optional<MemberResponse> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM lending_members WHERE id = ?")
                .param(id)
                .query(MemberRepository::mapDetail)
                .optional();
    }

    long countActive() {
        return jdbc.sql("SELECT count(*) FROM lending_members WHERE status = 'active'")
                .query(Long.class)
                .single();
    }

    /** The row, locked for the rest of the transaction (chapter 7 section 7.9). */
    Optional<MemberResponse> lockById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM lending_members WHERE id = ? FOR UPDATE")
                .param(id)
                .query(MemberRepository::mapDetail)
                .optional();
    }

    /** Writes the editable columns from {@code m}; branch, number, KYC and blacklist change elsewhere. */
    void update(MemberResponse m) {
        jdbc.sql("""
                        UPDATE lending_members SET full_name = :fullName, first_name = :firstName, last_name = :lastName,
                            phone_e164 = :phoneE164, alt_phone_e164 = :altPhoneE164, id_type = :idType,
                            national_id = :nationalId, other_id_number = :otherIdNumber, date_of_birth = :dateOfBirth,
                            gender = :gender, marital_status = :maritalStatus, district = :district,
                            sub_county = :subCounty, village = :village, location = :location, occupation = :occupation,
                            other_income_source = :otherIncomeSource, monthly_income_minor = :monthlyIncomeMinor,
                            officer_user_id = :officerUserId, status = :status, updated_at = now(), version = version + 1
                        WHERE id = :id
                        """).paramSource(m).update();
    }

    /** {@code verifiedBy} is set only for {@code verified}; the audit row names who rejected. */
    void decideKyc(UUID id, boolean verified, UUID decidedBy) {
        jdbc.sql("""
                        UPDATE lending_members SET kyc_status = CASE WHEN :verified THEN 'verified' ELSE 'rejected' END,
                            kyc_verified_by = CASE WHEN :verified THEN :by END,
                            kyc_verified_at = CASE WHEN :verified THEN now() END,
                            updated_at = now(), version = version + 1
                        WHERE id = :id
                        """)
                .param("verified", verified)
                .param("by", decidedBy)
                .param("id", id)
                .update();
    }

    void setBlacklist(UUID id, boolean blacklisted, String reason) {
        jdbc.sql("UPDATE lending_members SET is_blacklisted = ?, blacklist_reason = ?, updated_at = now(),"
                        + " version = version + 1 WHERE id = ?")
                .params(blacklisted, reason, id)
                .update();
    }

    /** @param excludeId the member being edited, or {@code null} on create */
    Optional<String> memberNoByNationalId(String nationalId, UUID excludeId) {
        return jdbc.sql("SELECT member_no FROM lending_members WHERE national_id = ? AND id IS DISTINCT FROM ?::uuid")
                .params(nationalId, excludeId)
                .query(String.class)
                .optional();
    }

    /** A likely duplicate with the reasons it matched: any of {@code nin}, {@code phone}, {@code name}. */
    record Candidate(
            UUID id,
            String memberNo,
            String fullName,
            UUID branchId,
            String phoneE164,
            String nationalId,
            List<String> reasons) {}

    /**
     * Likely duplicates across the whole tenant (FR-MEM-04), NIN matches first, then phone.
     * A null argument matches nothing; {@code excludeId} is the member being edited, or null.
     */
    List<Candidate> duplicateCandidates(String fullName, String phoneE164, String nationalId, UUID excludeId) {
        return jdbc.sql("""
                        SELECT id, member_no, full_name, branch_id, phone_e164, national_id,
                               coalesce(national_id = :nin, false) AS by_nin,
                               coalesce(phone_e164 = :phone, false) AS by_phone,
                               coalesce(full_name % :name AND similarity(full_name, :name) >= :similarity, false) AS by_name
                        FROM lending_members
                        WHERE (national_id = :nin OR phone_e164 = :phone
                               OR (full_name % :name AND similarity(full_name, :name) >= :similarity))
                          AND id IS DISTINCT FROM CAST(:exclude AS uuid)
                        ORDER BY by_nin DESC, by_phone DESC, member_no
                        LIMIT 20
                        """)
                .param("nin", nationalId, Types.VARCHAR)
                .param("phone", phoneE164, Types.VARCHAR)
                .param("name", fullName, Types.VARCHAR)
                .param("similarity", NAME_SIMILARITY)
                .param("exclude", excludeId)
                .query((rs, n) -> new Candidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("member_no"),
                        rs.getString("full_name"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("phone_e164"),
                        rs.getString("national_id"),
                        reasons(rs)))
                .list();
    }

    private static List<String> reasons(ResultSet rs) throws SQLException {
        List<String> reasons = new ArrayList<>();
        for (String reason : List.of("nin", "phone", "name")) {
            if (rs.getBoolean("by_" + reason)) {
                reasons.add(reason);
            }
        }
        return List.copyOf(reasons);
    }

    /**
     * One page ordered by member number. {@code branchIds} is already intersected with the
     * principal's branch scope; {@code null} means "every branch" for an all-branch principal.
     */
    List<MemberListItem> page(List<UUID> branchIds, List<String> statuses, String q, String afterMemberNo, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM lending_members WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (!statuses.isEmpty()) {
            sql.append(" AND status IN (:statuses)");
            params.put("statuses", statuses);
        }
        if (q != null && !q.isBlank()) {
            String term = q.trim();
            String digits = term.replaceAll("\\D", "");
            sql.append(" AND (member_no = :q OR national_id = upper(:q) OR full_name ILIKE :nameLike ESCAPE '\\'");
            if (digits.length() >= 4) {
                sql.append(" OR phone_e164 LIKE :phoneSuffix");
                params.put("phoneSuffix", "%" + digits);
            }
            sql.append(")");
            params.put("q", term);
            params.put(
                    "nameLike",
                    "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (afterMemberNo != null) {
            sql.append(" AND member_no > :after");
            params.put("after", afterMemberNo);
        }
        sql.append(" ORDER BY member_no LIMIT :limit");
        params.put("limit", limit);
        var statement = jdbc.sql(sql.toString()).params(params);
        return statement.query(MemberRepository::mapListItem).list();
    }

    private static MemberResponse mapDetail(ResultSet rs, int n) throws SQLException {
        return new MemberResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("member_no"),
                rs.getString("full_name"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("phone_e164"),
                rs.getString("alt_phone_e164"),
                rs.getString("id_type"),
                rs.getString("national_id"),
                rs.getString("other_id_number"),
                rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("gender"),
                rs.getString("marital_status"),
                rs.getString("district"),
                rs.getString("sub_county"),
                rs.getString("village"),
                rs.getString("location"),
                rs.getString("occupation"),
                rs.getString("other_income_source"),
                rs.getObject("monthly_income_minor", Long.class),
                rs.getString("currency"),
                rs.getString("kyc_status"),
                rs.getObject("kyc_verified_by", UUID.class),
                instant(rs.getTimestamp("kyc_verified_at")),
                rs.getString("status"),
                rs.getBoolean("is_blacklisted"),
                rs.getString("blacklist_reason"),
                rs.getObject("officer_user_id", UUID.class),
                rs.getString("source"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getInt("version"));
    }

    private static MemberListItem mapListItem(ResultSet rs, int n) throws SQLException {
        return new MemberListItem(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("member_no"),
                rs.getString("full_name"),
                Masking.lastFour(rs.getString("phone_e164")),
                Masking.lastFour(rs.getString("national_id")),
                rs.getString("kyc_status"),
                rs.getString("status"),
                rs.getBoolean("is_blacklisted"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
