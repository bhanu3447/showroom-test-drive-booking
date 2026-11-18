-- The showroom floor: four demo models (fictional names).
INSERT INTO cars (id, model, body_type, fuel, ex_showroom_price_inr, active) VALUES
    (1, 'Aurora EV',     'SUV',       'ELECTRIC', 1899000, TRUE),
    (2, 'Stratus 4x4',   'SUV',       'DIESEL',   1649000, TRUE),
    (3, 'Comet Hatch',   'HATCHBACK', 'PETROL',    749000, TRUE),
    (4, 'Vega Sedan',    'SEDAN',     'HYBRID',   1399000, TRUE),
    (5, 'Nova Classic',  'SEDAN',     'PETROL',    899000, FALSE);
