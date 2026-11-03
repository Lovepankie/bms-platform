-- V122: the applicant link outlives Activate (issue #746, FR-ONB-04, FR-ONB-07). V121 is the standard units (ADR-110).
--
-- onboarding_application_activated (V23) cleared link_token_hash and link_token_expires_at. The applicant's Confirm
-- (onboarding_application_by_link with p_verify) finds the row by that hash FOR UPDATE, so a Confirm still in flight
-- when the operator activated waited on the row, re-checked it after the commit, no longer matched and answered 404
-- link_invalid; a Confirm pressed later got the same, and the applicant page's "Your account is ready" could never
-- show. The link now keeps working until its own 7 day expiry: the page and Confirm show the activated application
-- and change nothing (by_link writes only for submitted, needs_info and verified rows). Expire still clears the link.
--
-- Expand only: the same signature, owner and grants. V23 is the only migration (SQL or Java) that ever defined this
-- function, so its body is the one on main after V121; this body is it without the two link assignments, nothing else.
-- It runs in the caller's transaction, which is already tenant-bound by TenantProvisioning, as before. Applications
-- activated before this migration keep the cleared link: the hash is gone, so their page cannot be brought back.

CREATE OR REPLACE FUNCTION onboarding_application_activated(
    p_id uuid, p_tenant_id uuid, p_platform_user_id uuid, p_way text, p_term text, p_note text, p_now timestamptz)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE onboarding_applications
       SET status = 'activated', activated_tenant = p_tenant_id, activated_by = p_platform_user_id,
           activated_at = p_now, activation_way = p_way, activation_term = p_term, activation_note = p_note,
           closed_at = p_now, updated_at = p_now
     WHERE id = p_id AND status = 'verified';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'application % is not verified', p_id USING ERRCODE = 'check_violation';
    END IF;
END
$$;
