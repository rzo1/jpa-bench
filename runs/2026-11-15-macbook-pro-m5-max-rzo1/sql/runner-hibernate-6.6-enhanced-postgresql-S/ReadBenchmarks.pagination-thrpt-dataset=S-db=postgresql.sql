select count(po1_0.ID) from ORDERS po1_0 where po1_0.STATUS=?;

select po1_0.ID,po1_0.CUSTOMER_ID,po1_0.ORDER_DATE,po1_0.STATUS,po1_0.TOTAL,po1_0.VERSION from ORDERS po1_0 where po1_0.STATUS=? order by po1_0.ORDER_DATE desc,po1_0.ID desc offset ? rows fetch first ? rows only;

