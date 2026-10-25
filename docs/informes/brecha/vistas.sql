-- The views of a corte's DuckDB (base.construir_duckdb runs this file after loading the 17 tables and
-- corte_manifiesto): the brecha by confianza, categoría and clase de construcción, and the predial in three
-- columns, the figures of the brecha report v3 (docs/superpowers/plans/2026-10-25-corte-analitico.md;
-- srtm-backend#205). Every statement is CREATE OR REPLACE so the file can be run again over the same base.
--
-- Amounts stay DECIMAL: in DuckDB DECIMAL / DECIMAL is a DOUBLE, so a percentage is taken as × 0.01 and nothing
-- divides before a rounding step that has to match srtm to the céntimo (plan, Rulings de la ejecución, R3).

-- The impuesto predial of a base (TUO LTM art. 13), as srtm's ImpuestoPredial.liquidar computes it: the base to
-- the céntimo, each tramo's tax to the céntimo, and never less than the mínimo (a % of the UIT) while there is a
-- base. DuckDB's round of a DECIMAL rounds half away from zero, srtm's RoundingMode.HALF_UP. alícuotas and the
-- mínimo come in %, the límites in UIT, the UIT in soles. Like srtm, no figure (NULL) when a parameter is missing;
-- the base has to be exact (a DECIMAL or an integer), never a DOUBLE.
CREATE OR REPLACE MACRO _impuesto_predial_tramos(b, uit, a1, a2, a3, l1, l2, minimo) AS
    CASE WHEN b IS NULL OR b <= 0 THEN CAST(0 AS DECIMAL(38, 2)) ELSE greatest(
        round(uit * minimo * 0.01, 2),
        round(least(b, round(l1 * uit, 2)) * a1 * 0.01, 2)
        + round(greatest(least(b, round(l2 * uit, 2)) - round(l1 * uit, 2), 0) * a2 * 0.01, 2)
        + round(greatest(b - round(l2 * uit, 2), 0) * a3 * 0.01, 2)) END;

CREATE OR REPLACE MACRO impuesto_predial(base, uit, a1, a2, a3, l1, l2, minimo) AS
    CASE WHEN uit IS NULL OR a1 IS NULL OR a2 IS NULL OR a3 IS NULL OR l1 IS NULL OR l2 IS NULL OR minimo IS NULL
        THEN NULL
    ELSE _impuesto_predial_tramos(
        round(base, 2),
        CAST(uit AS DECIMAL(38, 10)), CAST(a1 AS DECIMAL(38, 10)), CAST(a2 AS DECIMAL(38, 10)),
        CAST(a3 AS DECIMAL(38, 10)), CAST(l1 AS DECIMAL(38, 10)), CAST(l2 AS DECIMAL(38, 10)),
        CAST(minimo AS DECIMAL(38, 10))) END;

-- The predial's parameters in force on 1 January of the corte's año (LTM art. 10), one row: the latest
-- vigencia_desde of each (tie: the greatest id), as srtm's enVigor. Tramos and límites are told apart by clave;
-- srtm also asks the UIT and the mínimo for an empty clave, here any clave is taken.
CREATE OR REPLACE VIEW v_parametros_predial AS
WITH vigentes AS (
    SELECT p.tipo, trim(p.clave) AS clave, p.valor_numerico, p.vigencia_desde, p.id
    FROM srtm_parametro p, corte_manifiesto m
    WHERE p.valor_numerico IS NOT NULL
      AND coalesce(trim(p.estado), '') IN ('', 'VIGENTE')
      AND p.vigencia_desde <= make_date(m.anio, 1, 1)
      AND (p.vigencia_hasta IS NULL OR p.vigencia_hasta >= make_date(m.anio, 1, 1))
)
SELECT
    first(valor_numerico ORDER BY vigencia_desde DESC, id DESC) FILTER (tipo = 'UIT') AS uit,
    first(valor_numerico ORDER BY vigencia_desde DESC, id DESC) FILTER (tipo = 'TRAMO_PREDIAL' AND clave = '1') AS a1,
    first(valor_numerico ORDER BY vigencia_desde DESC, id DESC) FILTER (tipo = 'TRAMO_PREDIAL' AND clave = '2') AS a2,
    first(valor_numerico ORDER BY vigencia_desde DESC, id DESC) FILTER (tipo = 'TRAMO_PREDIAL' AND clave = '3') AS a3,
    first(valor_numerico ORDER BY vigencia_desde DESC, id DESC)
        FILTER (tipo = 'TRAMO_PREDIAL_LIMITE' AND clave = '1') AS l1,
    first(valor_numerico ORDER BY vigencia_desde DESC, id DESC)
        FILTER (tipo = 'TRAMO_PREDIAL_LIMITE' AND clave = '2') AS l2,
    first(valor_numerico ORDER BY vigencia_desde DESC, id DESC) FILTER (tipo = 'PREDIAL_MINIMO') AS minimo
FROM vigentes;

-- The clase de construcción of each ficha: the clasificación with the most área construida added up (tie: the
-- first alphabetically); constructions without a clasificación only count when none has one (SIN CLASIFICACION);
-- a ficha without constructions is SIN CONSTRUCCION.
CREATE OR REPLACE VIEW v_clase_predominante AS
WITH areas AS (
    SELECT codigo_municipal, nullif(trim(clasificacion), '') AS clasificacion,
           sum(coalesce(area_construida, 0)) AS area
    FROM catastro_construccion
    GROUP BY ALL
),
predominante AS (
    SELECT codigo_municipal,
           first(clasificacion ORDER BY area DESC, clasificacion) FILTER (clasificacion IS NOT NULL) AS clase
    FROM areas
    GROUP BY codigo_municipal
)
SELECT f.codigo_municipal,
       CASE WHEN p.codigo_municipal IS NULL THEN 'SIN CONSTRUCCION'
            ELSE coalesce(p.clase, 'SIN CLASIFICACION') END AS clase
FROM (SELECT DISTINCT codigo_municipal FROM catastro_ficha WHERE codigo_municipal IS NOT NULL) f
LEFT JOIN predominante p ON p.codigo_municipal = f.codigo_municipal
ORDER BY f.codigo_municipal;

-- Each brecha_predio with its ficha and clase. The join is on catastro's code (codigo_catastro), never on srtm's
-- predio.codigo, which splits a shared code into <codigo>-<n> (plan, Rulings de la ejecución, R7); one ficha per
-- code (the lowest id) so a repeated code cannot count a predio twice. A NULL confianza (an srtm without #204) shows as
-- SIN_DATO: the confianza rule is srtm's and is never derived here.
CREATE OR REPLACE VIEW v_brecha_predio AS
WITH ficha AS (
    SELECT codigo_municipal, estado_levantamiento, vinculo_metodo, vinculo_revisado
    FROM catastro_ficha
    WHERE codigo_municipal IS NOT NULL
    QUALIFY row_number() OVER (PARTITION BY codigo_municipal ORDER BY id) = 1
)
SELECT b.* REPLACE (coalesce(nullif(trim(b.confianza), ''), 'SIN_DATO') AS confianza),
       f.estado_levantamiento, f.vinculo_metodo, f.vinculo_revisado, c.clase
FROM srtm_brecha_predio b
LEFT JOIN ficha f ON f.codigo_municipal = b.codigo_catastro
LEFT JOIN v_clase_predominante c ON c.codigo_municipal = b.codigo_catastro
ORDER BY b.orden, b.id;

CREATE OR REPLACE VIEW v_segmentos AS
SELECT confianza, categoria, count(*) AS predios, sum(delta_impuesto) AS delta_impuesto,
       sum(delta_autoavaluo) AS delta_autoavaluo, sum(impuesto_declarado) AS impuesto_declarado,
       sum(impuesto_catastro) AS impuesto_catastro
FROM v_brecha_predio
GROUP BY confianza, categoria
ORDER BY confianza, categoria;

CREATE OR REPLACE VIEW v_brecha_confiable_por_categoria AS
SELECT categoria, predios, delta_impuesto, delta_autoavaluo
FROM v_segmentos
WHERE confianza = 'CONFIABLE'
ORDER BY categoria;

-- clase is NULL for a brecha_predio whose code has no ficha
CREATE OR REPLACE VIEW v_brecha_por_clase AS
SELECT confianza, clase, count(*) AS predios, sum(delta_impuesto) AS delta_impuesto,
       sum(delta_autoavaluo) AS delta_autoavaluo
FROM v_brecha_predio
GROUP BY confianza, clase
ORDER BY confianza, clase;

-- The predial in three columns per contribuyente (plan § Rulings; plan, Rulings de la ejecución, R4), over the
-- vigentes DJ of the año (estado empty or VIGENTE):
--   declarado: impuesto_predial once over the sum of the contribuyente's bases; a DJ's base is the origin
--     autoavalúo (valor_terreno + valor_construccion + valor_otras_instalaciones) × porcentaje_condominio / 100
--     − deduccion, or its valor_afecto when it carries none of the three;
--   motor_rentas: what srtm liquidated (srtm_impuesto_contribuyente.impuesto_anual);
--   motor_catastro: each predio's brecha impuesto_catastro split among its vigentes DJ in proportion to their
--     valor_afecto (in equal parts when none has one), to the céntimo, the last DJ by id taking what rounding
--     left so the parts add up to the predio's figure; a predio without a vigente DJ goes to no one.
-- A contribuyente without a vigente DJ still shows when srtm liquidated it.
CREATE OR REPLACE VIEW v_predial_contribuyente AS
WITH dj AS (
    SELECT d.id, d.predio, d.contribuyente, d.valor_afecto,
           CASE WHEN coalesce(d.valor_terreno, d.valor_construccion, d.valor_otras_instalaciones) IS NULL
                THEN d.valor_afecto
                ELSE (coalesce(d.valor_terreno, 0) + coalesce(d.valor_construccion, 0)
                      + coalesce(d.valor_otras_instalaciones, 0))
                     * coalesce(d.porcentaje_condominio, 100) * 0.01 - coalesce(d.deduccion, 0) END AS base
    FROM srtm_declaracion d, corte_manifiesto m
    WHERE d.anio = m.anio AND coalesce(trim(d.estado), '') IN ('', 'VIGENTE')
),
declarado AS (
    SELECT s.contribuyente, impuesto_predial(s.base, p.uit, p.a1, p.a2, p.a3, p.l1, p.l2, p.minimo) AS declarado
    FROM (SELECT contribuyente, sum(base) AS base FROM dj GROUP BY contribuyente) s, v_parametros_predial p
),
partes AS (
    SELECT b.id AS brecha, dj.contribuyente, b.impuesto_catastro,
           -- the weights are the only DOUBLE: a part is rounded to the céntimo and the remainder fixes the sum
           CAST(round(CASE WHEN sum(coalesce(dj.valor_afecto, 0)) OVER predio > 0
                           THEN b.impuesto_catastro * coalesce(dj.valor_afecto, 0)
                                / sum(coalesce(dj.valor_afecto, 0)) OVER predio
                           ELSE b.impuesto_catastro / count(*) OVER predio END, 2) AS DECIMAL(38, 2)) AS parte,
           row_number() OVER (predio ORDER BY dj.id DESC) = 1 AS ultima
    FROM srtm_brecha_predio b
    JOIN dj ON dj.predio = b.predio
    WINDOW predio AS (PARTITION BY b.id)
),
ajustadas AS (
    SELECT contribuyente,
           CASE WHEN ultima THEN impuesto_catastro - (sum(parte) OVER (PARTITION BY brecha) - parte)
                ELSE parte END AS parte
    FROM partes
),
motor_catastro AS (
    SELECT contribuyente, sum(parte) AS motor_catastro FROM ajustadas GROUP BY contribuyente
),
rentas AS (
    SELECT i.contribuyente, i.impuesto_anual
    FROM srtm_impuesto_contribuyente i, corte_manifiesto m
    WHERE i.anio = m.anio
),
contribuyentes AS (
    SELECT contribuyente FROM dj
    UNION
    SELECT contribuyente FROM rentas
)
SELECT c.contribuyente, sc.documento_hmac, d.declarado, r.impuesto_anual AS motor_rentas, mc.motor_catastro
FROM contribuyentes c
LEFT JOIN srtm_contribuyente sc ON sc.id = c.contribuyente
LEFT JOIN declarado d ON d.contribuyente = c.contribuyente
LEFT JOIN rentas r ON r.contribuyente = c.contribuyente
LEFT JOIN motor_catastro mc ON mc.contribuyente = c.contribuyente
WHERE c.contribuyente IS NOT NULL
ORDER BY c.contribuyente;

CREATE OR REPLACE VIEW v_predial_tres_columnas AS
SELECT count(*) AS contribuyentes, sum(declarado) AS declarado, sum(motor_rentas) AS motor_rentas,
       sum(motor_catastro) AS motor_catastro
FROM v_predial_contribuyente;
