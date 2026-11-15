select c1_0.ID,c1_0.CITY,c1_0.COUNTRY,c1_0.STREET,c1_0.ZIP,c1_0.CREATED_AT,c1_0.EMAIL,c1_0.FIRST_NAME,c1_0.LAST_NAME from CUSTOMER c1_0 where c1_0.CITY=? and c1_0.CREATED_AT>=? order by c1_0.LAST_NAME,c1_0.ID fetch first ? rows only;

