-- Coupon codes read from a deal's own write-up. Slickdeals' RSS body names them ("w/ code K8F6E3VU",
-- marked up as <span data-role='couponCode'>), and the price in the title is often the price only
-- after the code -- so without it the feed showed a price you could not get.
--
-- NULL = never read (every source but Slickdeals, and Slickdeals rows from before this); an empty
-- array = read, and it names none. coupon_clip is Amazon's "clip the coupon on the product page",
-- a discount with nothing to type.
ALTER TABLE deals
    ADD COLUMN coupon_codes text[],
    ADD COLUMN coupon_clip  boolean;
