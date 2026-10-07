-- MemberRepository.page
-- tenant: lending_tenant
SELECT id, branch_id, member_no, full_name, first_name, last_name, phone_e164, alt_phone_e164, id_type,
       national_id, other_id_number, date_of_birth, gender, marital_status, district, sub_county, village,
       location, occupation, other_income_source, monthly_income_minor, currency, kyc_status, kyc_verified_by,
       kyc_verified_at, status, is_blacklisted, blacklist_reason, officer_user_id, source, created_at, updated_at
  FROM lending_members WHERE true AND status IN ('active') ORDER BY member_no LIMIT 51
