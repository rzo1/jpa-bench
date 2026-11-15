select p1_0.ID,p1_0.NAME,p1_0.PRICE,p1_0.SKU from PRODUCT p1_0 where p1_0.ID between ? and ? order by p1_0.ID;

select c1_0.PRODUCT_ID,c1_1.ID,c1_1.NAME from PRODUCT_CATEGORY c1_0 join CATEGORY c1_1 on c1_1.ID=c1_0.CATEGORY_ID where c1_0.PRODUCT_ID=?;

select t1_0.PRODUCT_ID,t1_0.TAG from PRODUCT_TAG t1_0 where t1_0.PRODUCT_ID=?;

