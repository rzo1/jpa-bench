select jp1_0.ID,jp1_0.PTYPE,jp1_0.EMAIL,jp1_0.NAME,jp1_1.AGENCY,jp1_1.HOURLY_RATE,jp1_2.DEPARTMENT,jp1_2.SALARY from JN_PERSON jp1_0 left join JN_CONTRACTOR jp1_1 on jp1_0.ID=jp1_1.ID left join JN_EMPLOYEE jp1_2 on jp1_0.ID=jp1_2.ID where jp1_0.ID between ? and ? order by jp1_0.ID;

