-- "Booking for" (a name typed for someone else) is gone: without that person's contact details it added nothing.
-- Messages, the start code and strikes always belong to the account that booked.
ALTER TABLE appointments DROP COLUMN booking_for;
