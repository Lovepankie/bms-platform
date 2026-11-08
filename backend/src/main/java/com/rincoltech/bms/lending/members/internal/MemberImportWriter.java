package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.PlanLimits;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.lending.members.MemberImports;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MemberImports} over {@code lending_members} and {@code lending_next_of_kin} (issue #136). Plain SQL in the caller's
 * transaction; every lookup names the business ({@code tenant_id = current_setting('app.tenant_id')}) as well as
 * row-level security does (ADR-003), defence in depth (Lead on #877). The audit rows name ids and numbers
 * only, never a name, phone or NIN of the register.
 */
@Component
class MemberImportWriter implements MemberImports {

    private final JdbcClient jdbc;
    private final MemberRepository members;
    private final TenantSequences sequences;
    private final PlanLimits planLimits;
    private final AuditLog audit;

    MemberImportWriter(
            JdbcClient jdbc,
            MemberRepository members,
            TenantSequences sequences,
            PlanLimits planLimits,
            AuditLog audit) {
        this.jdbc = jdbc;
        this.members = members;
        this.sequences = sequences;
        this.planLimits = planLimits;
        this.audit = audit;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Held> byNin(String nationalId) {
        return jdbc.sql("SELECT id, branch_id, national_id, phone_e164, full_name FROM lending_members"
                        + " WHERE tenant_id = current_setting('app.tenant_id')::uuid AND national_id = ?")
                .param(nationalId)
                .query(MemberImportWriter::held)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Held> byPhone(String phoneE164) {
        return jdbc.sql("SELECT id, branch_id, national_id, phone_e164, full_name FROM lending_members"
                        + " WHERE tenant_id = current_setting('app.tenant_id')::uuid AND phone_e164 = ?"
                        + " ORDER BY created_at, id")
                .param(phoneE164)
                .query(MemberImportWriter::held)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean exists(UUID memberId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending_members"
                        + " WHERE tenant_id = current_setting('app.tenant_id')::uuid AND id = ?)")
                .param(memberId)
                .query(Boolean.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID create(NewMember m) {
        planLimits.checkRoomFor(PlanLimits.MAX_ACTIVE_MEMBERS, members.countActive());
        UUID id = UUID.randomUUID();
        String memberNo = "M%06d".formatted(sequences.next("member_no"));
        jdbc.sql("""
                        INSERT INTO lending_members (
                            id, tenant_id, branch_id, member_no, full_name, phone_e164, alt_phone_e164, id_type,
                            national_id, other_id_number, marital_status, location, occupation, other_income_source,
                            currency, kyc_status, status, officer_user_id, source, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            'incomplete', 'active', ?, 'import', ?)
                        """)
                .params(
                        id,
                        m.branchId(),
                        memberNo,
                        m.fullName(),
                        m.phoneE164(),
                        m.altPhoneE164(),
                        m.idType(),
                        m.nationalId(),
                        m.otherIdNumber(),
                        m.maritalStatus(),
                        m.location(),
                        m.occupation(),
                        m.otherIncomeSource(),
                        m.currency(),
                        m.officerUserId(),
                        m.createdBy())
                .update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("member_no", memberNo);
        after.put("branch_id", m.branchId());
        after.put("source", "import");
        audit.record(AuditLog.Entry.created("lending.member.imported", "lending.member", id, m.branchId(), after));
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID addKin(NewKin k) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO lending_next_of_kin (id, tenant_id, member_id, full_name, phone_e164, national_id,
                            relationship, relationship_text, location, is_primary, linked_member_id, link_method,
                            link_status, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        k.memberId(),
                        k.fullName(),
                        k.phoneE164(),
                        k.nationalId(),
                        k.relationship(),
                        k.relationshipText(),
                        k.location(),
                        k.primary(),
                        k.linkedMemberId(),
                        k.linkMethod(),
                        k.linkStatus(),
                        k.createdBy())
                .update();
        return id;
    }

    // JUSTIFICATION-A3: a new method of the new port, chapter 13 section 13.9's next of kin collapse.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public KinHeld kinOf(UUID memberId, String nationalId, String phoneE164, String fullName) {
        UUID same = jdbc.sql("""
                        SELECT id FROM lending_next_of_kin
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND member_id = ?
                           AND (national_id = ? OR phone_e164 = ?
                                OR (national_id IS NULL AND phone_e164 IS NULL AND CAST(? AS text) IS NULL
                                    AND CAST(? AS text) IS NULL AND lower(full_name) = lower(?)))
                         ORDER BY created_at, id LIMIT 1
                        """)
                .params(memberId, nationalId, phoneE164, nationalId, phoneE164, fullName)
                .query(UUID.class)
                .optional()
                .orElse(null);
        boolean primary = jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending_next_of_kin WHERE tenant_id ="
                        + " current_setting('app.tenant_id')::uuid AND member_id = ? AND is_primary)")
                .param(memberId)
                .query(Boolean.class)
                .single();
        return new KinHeld(same, primary);
    }

    private static Held held(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Held(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("national_id"),
                rs.getString("phone_e164"),
                rs.getString("full_name"));
    }
}
