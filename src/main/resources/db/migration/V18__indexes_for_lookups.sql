-- On a live database: wait at most 3 s for each table's lock (a copy of the backend still running holds them for its
-- writes). Without it, a migration queues behind those writes and every write after it queues behind the migration;
-- with it, the deploy fails fast and is simply run again (see "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- Indexes for lookups and foreign-key checks that still read whole tables. (Plain CREATE INDEX: the tables are still
-- small; on large tables this would be CREATE INDEX CONCURRENTLY in a migration of its own.)

-- Deleting a file checks no barber uses it as his photo (the foreign key, and SalonStaffRepository.existsByPhotoFileId)
CREATE INDEX ix_salon_staff_photo ON salon_staff (photo_file_id) WHERE photo_file_id IS NOT NULL;

-- The admin team page and test mode's checks look for admins among all users
CREATE INDEX ix_users_admins ON users (admin_role) WHERE admin_role <> 'NONE';

-- Every barber's hours are read with staff_id IN (...) (the day board, free times, the barbers list). The no-overlap
-- rule's GiST index starts with staff_id but can't answer an IN list, so it read the whole table
CREATE INDEX ix_staff_hours_staff ON staff_hours (staff_id);

-- A salon's closed days (GET /salon/closures, and a barber added or switched on joining them): only its CLOSED rows,
-- instead of every appointment it ever had
CREATE INDEX ix_appointments_shop_closures ON appointments (shop_id, starts_at)
    WHERE closure_id IS NOT NULL AND status = 'BLOCKED';

-- Foreign keys checked, and why the others need nothing:
--   already indexed: files.shop_id, refresh_tokens.user_id, shop_members.shop_id and user_id, shops' two photos (V13),
--   salon_staff.shop_id and user_id, salon_services.shop_id, staff_services (staff_id, service_id), appointments
--   shop_id / customer_id / closure_id / (created_by, client_request_id), appointment_services.appointment_id,
--   appointment_events.appointment_id, customer_strikes.customer_id and appointment_id, walk_in_access (shop_id,
--   user_id) and user_id (V13), notification_jobs.user_id and about_user_id, push_subscriptions.user_id,
--   shop_events.shop_id, salon_pauses, salon_settings and account_wipes (their primary keys), salon_versions.
--   not needed: every key pointing at users (files.uploaded_by, appointments.created_by, appointment_events.by_user,
--   walk_in_access.granted_by, salon_pauses.updated_by, admin_events.by_user, test_phones.added_by,
--   otp_test_mode.turned_on_by, shop_events.by_user): users are never deleted (an account deletion keeps the row),
--   and nothing looks rows up by these columns; staff_services.service_id and appointment_services.service_id:
--   services are switched off, never deleted, and nothing looks up by them; appointments.staff_id: found through the
--   no-overlap rule's index (staff_id, time_range) for every status that holds time, and barbers are never deleted;
--   *_aud.rev: history is never deleted.
