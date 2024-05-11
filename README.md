# lineage

# TODO

## select parser

1. select c1,c2
2. select * from A
3. select * from A,B
4. select c1,c2 from A,B //c1 from A or B
5. select c1+c2 from A //expressions
6. select max(c1) from A

## lineage map

1. build lineage map by direct relations
2. export global lineage for each target fields

## 设计模式

使用 visitor 模式来实现血缘关系的分析，参考 SelectLineage.java(代码来自 net.sf.jsqlparser.util.TablesNamesFinder，只实现了找表级血缘)。