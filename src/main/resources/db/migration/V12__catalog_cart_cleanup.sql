-- Quantity PATCH is an absolute, member-owned update; no version check is needed.
ALTER TABLE cart_items DROP COLUMN version;
-- There is no anonymous-cart staging route or consumer.
DROP TABLE cart_guest_items;
DROP TABLE cart_guest_sessions;
