select po1_0.ID,po1_0.CUSTOMER_ID,po1_0.ORDER_DATE,po1_0.STATUS,po1_0.TOTAL,po1_0.VERSION from ORDERS po1_0 where po1_0.ID between ? and ?;

select l1_0.ORDER_ID,l1_0.ID,l1_0.LINE_NO,l1_0.PRODUCT_ID,l1_0.QUANTITY,l1_0.UNIT_PRICE from ORDER_LINE l1_0 where l1_0.ORDER_ID=?;

delete from ORDER_LINE where ID=?;

delete from ORDERS where ID=? and VERSION=?;

