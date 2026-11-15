select po1_0.ID,po1_0.CUSTOMER_ID,po1_0.ORDER_DATE,po1_0.STATUS,po1_0.TOTAL,po1_0.VERSION from ORDERS po1_0 where po1_0.ID between ? and ? order by po1_0.ID;

update ORDERS set CUSTOMER_ID=?,ORDER_DATE=?,STATUS=?,TOTAL=?,VERSION=? where ID=? and VERSION=?;

