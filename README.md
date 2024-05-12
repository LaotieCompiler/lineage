# lineage

## 设计模式

使用 visitor 模式来实现血缘关系的分析，参考 SelectLineage.java(代码来自 net.sf.jsqlparser.util.TablesNamesFinder，只实现了找表级血缘)。

## Feature

1. select 1,2  //ignore constants
2. select 1 as c1 from A  //ignore constnts
1. select c1+c2 from A
2. select max(c1) from A
3. select a1,a2 from (select b1, b2 from (select c1,c2 from C))
4. SELECT TB.id as bid, TC.id as cid, id as aid FROM TA Left Join (select id, ba1 b1, ba2 b2 from TBA )TB ON TA.id = TB.id

# TODO

## select parser

3. select * from A  //dependence on metadata
4. select c1, c2 from A as TA
5. select * from A,B
6. select c1,c2 from A,B //c1 from A or B
7. select A.c1,B.c2 from A,B 
8. specify default database/schema


## lineage map

1. build lineage map by direct relations
2. export global lineage for each target fields

