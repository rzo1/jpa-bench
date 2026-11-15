select c1_0.CITY,count(po1_0.ID),sum(po1_0.TOTAL) from ORDERS po1_0 join CUSTOMER c1_0 on c1_0.ID=po1_0.CUSTOMER_ID where po1_0.ORDER_DATE between ? and ? group by c1_0.CITY order by c1_0.CITY;

