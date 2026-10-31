CREATE TABLE orders (
    id UUID PRIMARY KEY,
    customer_id VARCHAR(128) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total NUMERIC(18, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC, id DESC);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    line_number INTEGER NOT NULL CHECK (line_number >= 0),
    unit_price NUMERIC(10, 2) NOT NULL CHECK (unit_price > 0),
    UNIQUE (order_id, line_number)
);
