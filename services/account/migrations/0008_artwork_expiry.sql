-- Artwork expires a day after its last use. Rows stored as permanent get one day from deploy.
UPDATE artwork SET expires_at = unixepoch() + 86400 WHERE storage = 'b2';
