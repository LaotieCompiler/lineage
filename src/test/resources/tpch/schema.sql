CREATE TABLE region (
    r_regionkey INT,
    r_name VARCHAR(25),
    r_comment VARCHAR(152)
);
CREATE TABLE nation (
    n_nationkey INT,
    n_name VARCHAR(25),
    n_regionkey INT,
    n_comment VARCHAR(152)
);
CREATE TABLE part (
    p_partkey INT,
    p_name VARCHAR(55),
    p_mfgr VARCHAR(25),
    p_brand VARCHAR(10),
    p_type VARCHAR(25),
    p_size INT,
    p_container VARCHAR(10),
    p_retailprice DECIMAL(15, 2),
    p_comment VARCHAR(23)
);
CREATE TABLE supplier (
    s_suppkey INT,
    s_name VARCHAR(25),
    s_address VARCHAR(40),
    s_nationkey INT,
    s_phone VARCHAR(15),
    s_acctbal DECIMAL(15, 2),
    s_comment VARCHAR(101)
);
CREATE TABLE partsupp (
    ps_partkey INT,
    ps_suppkey INT,
    ps_availqty INT,
    ps_supplycost DECIMAL(15, 2),
    ps_comment VARCHAR(199)
);
CREATE TABLE customer (
    c_custkey INT,
    c_name VARCHAR(25),
    c_address VARCHAR(40),
    c_nationkey INT,
    c_phone VARCHAR(15),
    c_acctbal DECIMAL(15, 2),
    c_mktsegment VARCHAR(10),
    c_comment VARCHAR(117)
);
CREATE TABLE orders (
    o_orderkey INT,
    o_custkey INT,
    o_orderstatus CHAR(1),
    o_totalprice DECIMAL(15, 2),
    o_orderdate DATE,
    o_orderpriority CHAR(15),
    o_clerk CHAR(15),
    o_shippriority INT,
    o_comment VARCHAR(79)
);
CREATE TABLE lineitem (
    l_orderkey INT,
    l_partkey INT,
    l_suppkey INT,
    l_linenumber INT,
    l_quantity DECIMAL(15, 2),
    l_extendedprice DECIMAL(15, 2),
    l_discount DECIMAL(15, 2),
    l_tax DECIMAL(15, 2),
    l_returnflag CHAR(1),
    l_linestatus CHAR(1),
    l_shipdate DATE,
    l_commitdate DATE,
    l_receiptdate DATE,
    l_shipinstruct CHAR(25),
    l_shipmode CHAR(10),
    l_comment VARCHAR(44)
);
