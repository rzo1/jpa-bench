select sp1_0.ID,sp1_0.PTYPE,sp1_0.EMAIL,sp1_0.NAME,sp1_0.AGENCY,sp1_0.HOURLY_RATE,sp1_0.DEPARTMENT,sp1_0.SALARY from ST_PERSON sp1_0 where sp1_0.ID between ? and ? order by sp1_0.ID;

