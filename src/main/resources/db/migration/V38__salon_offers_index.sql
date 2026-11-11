-- Built CONCURRENTLY, without a transaction and with no lock timeout, for the reasons given in V24: bookings go on
-- while it builds. If the build fails, see V24 and "Database changes" in the README (the DROP lets it run again).
SET lock_timeout = 0;

-- The bookings an offer brought, counted for the owner's list (SalonOfferStore.LISTED and bookings).
DROP INDEX CONCURRENTLY IF EXISTS ix_appointment_services_offer;
CREATE INDEX CONCURRENTLY ix_appointment_services_offer ON appointment_services (offer_id)
    WHERE offer_id IS NOT NULL;

-- The salon's past customers an offer message goes to (SalonOfferStore.RECIPIENTS): its completed online bookings,
-- without reading its walk-ins, blocks and every other booking.
DROP INDEX CONCURRENTLY IF EXISTS ix_appointments_completed_customers;
CREATE INDEX CONCURRENTLY ix_appointments_completed_customers ON appointments (shop_id, customer_id)
    WHERE kind = 'BOOKING' AND status = 'COMPLETED';
