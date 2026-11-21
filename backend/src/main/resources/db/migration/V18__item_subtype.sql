-- Base item subtype (GW2 API details.type, e.g. LongBow/Coat); NULL until a metadata batch retains it.
ALTER TABLE items ADD COLUMN subtype TEXT;
