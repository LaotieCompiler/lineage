SELECT max(Col1) as Mcol1, Col2, Col3, 1 as Col4
FROM (
        SELECT A1 Col1, A2 Col2, A3 Col3
        FROM (
                SELECT B1 + B2 as A1, B1 + B2 * B3 as A2, B3 A3
                FROM (
                        SELECT 1 B1, 2 B2, 3 B3
                    )
            )
    )
WHERE
    condition;

SELECT TB.id as bid, TC.id as cid, id as aid
FROM TA
    LEFT JOIN (
        SELECT id, ba1 b1, ba2 b2
        FROM TBA
    ) TB ON TA.id = TB.id
    Right Join TC ON TB.id = TC.id
WHERE
    condition;

SELECT * FROM A AS TA;

SELECT C + D as A, D as B
From (
        SELECT E as C, F as D
        From T
    )

SELECT C + D as A, D as B, C + TB.C1 as A1, TB.D1 as B1
From (
        SELECT E as C, F as D
        From T
    ) as TA
    INNER JOIN TB on TA.aid = TB.bid

INSERT INTO
    TTT (col1, col2, col3, col4)
SELECT C + D as A, D as B, C + TB.C1 as A1, TB.D1 as B1
From (
        SELECT E as C, F as D
        From T
    ) as TA
    INNER JOIN TB on TA.aid = TB.bid

INSERT INTO
    TTT (col1, col2, col3, col4)
SELECT C + D as A, D as B, C + TB.C1 as A1, TB.D1 as B1
From (
        SELECT E as C, F as D
        From T
    ) as TA
    INNER JOIN TB on TA.aid = TB.bid
WHERE
    TA.C IN (
        SELECT C
        FROM TC
    );

INSERT INTO
    TTT1
SELECT C + D as A, D as B, C + TB.C1 as A1, TB.*
From (
        SELECT E as C, F as D
        From T
    ) as TA
    INNER JOIN TB on TA.aid = TB.bid;

INSERT INTO
    stat_dim_numbers (n)
WITH RECURSIVE
    seq AS (
        SELECT 1 AS n
        UNION ALL
        SELECT n + 1
        FROM seq
        WHERE
            n < 500
    )
SELECT n
FROM seq;


INSERT INTO TB(cola,colb)  
SELECT * FROM TA;