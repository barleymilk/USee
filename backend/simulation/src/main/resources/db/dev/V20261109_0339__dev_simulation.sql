-- 개발·시연용 모의 입양 더미(local·dev 전용). 더미 회원 130~134(S1 dev_base). 종료 세션 2건 + 리포트, 진행 중 1건
-- 품종은 core 카탈로그 시드에서 DOG·CAT 첫 품종을 읽는다(교차 FK 없음). 카탈로그가 아직 없으면 아무것도 넣지 않는다
DO $$
DECLARE
    v_dog_breed INTEGER; v_dog_personality VARCHAR(20);
    v_cat_breed INTEGER; v_cat_personality VARCHAR(20);
    v_session BIGINT; v_walk BIGINT; v_occ BIGINT; v_log BIGINT;
    v_start TIMESTAMPTZ;
    i_bowl BIGINT; i_water BIGINT; i_dog_food BIGINT; i_dog_snack BIGINT; i_toy BIGINT;
    i_cat_food BIGINT; i_litter_box BIGINT; i_litter BIGINT;
    s_vet BIGINT; s_bark BIGINT; s_litter BIGINT;
BEGIN
    SELECT b.breed_seq, b.personality INTO v_dog_breed, v_dog_personality
    FROM core.animal_breeds b JOIN core.animal_species s ON s.species_seq = b.species_seq
    WHERE s.species_code = 'DOG' ORDER BY b.breed_seq LIMIT 1;
    SELECT b.breed_seq, b.personality INTO v_cat_breed, v_cat_personality
    FROM core.animal_breeds b JOIN core.animal_species s ON s.species_seq = b.species_seq
    WHERE s.species_code = 'CAT' ORDER BY b.breed_seq LIMIT 1;
    IF v_dog_breed IS NULL OR v_cat_breed IS NULL THEN
        RAISE NOTICE 'core 카탈로그 시드가 없어 모의 입양 더미를 건너뜁니다';
        RETURN;
    END IF;

    SELECT item_seq INTO i_bowl FROM simulation_shop_items WHERE category = 'FOOD_BOWL' LIMIT 1;
    SELECT item_seq INTO i_water FROM simulation_shop_items WHERE category = 'WATER_BOWL' LIMIT 1;
    SELECT item_seq INTO i_dog_food FROM simulation_shop_items WHERE category = 'FOOD' AND species_scope = 'DOG' LIMIT 1;
    SELECT item_seq INTO i_dog_snack FROM simulation_shop_items WHERE category = 'SNACK' AND species_scope = 'DOG' LIMIT 1;
    SELECT item_seq INTO i_toy FROM simulation_shop_items WHERE category = 'TOY' AND species_scope = 'DOG' LIMIT 1;
    SELECT item_seq INTO i_cat_food FROM simulation_shop_items WHERE category = 'FOOD' AND species_scope = 'CAT' LIMIT 1;
    SELECT item_seq INTO i_litter_box FROM simulation_shop_items WHERE category = 'LITTER_BOX' LIMIT 1;
    SELECT item_seq INTO i_litter FROM simulation_shop_items WHERE category = 'LITTER' LIMIT 1;
    SELECT scenario_seq INTO s_vet FROM simulation_event_scenarios WHERE resolve_action_code = 'VET_VISIT' LIMIT 1;
    SELECT scenario_seq INTO s_bark FROM simulation_event_scenarios WHERE trigger_context = 'WALK' AND personality_scope IS NULL ORDER BY scenario_seq LIMIT 1;
    SELECT scenario_seq INTO s_litter FROM simulation_event_scenarios WHERE resolve_action_code = 'CLEAN_LITTER' LIMIT 1;

    -- ===== 1) 회원 130: 개, 7일 완주(COMPLETED) =====
    v_start := now() - INTERVAL '10 days';
    INSERT INTO simulation_sessions (user_seq, breed_seq, animal_name, species_type, personality, status, expected_cost, balance,
        started_dttm, ends_dttm, ended_dttm, satiety, hydration, cleanliness, bond, calmness, health, energy,
        stats_calculated_dttm, last_action_dttm)
    VALUES (130, v_dog_breed, '초코', 'DOG', v_dog_personality, 'COMPLETED', 150000, 150000 - 12000 - 9000 - 40000 - 6000 - 15000 - 30000,
        v_start, v_start + INTERVAL '7 days', v_start + INTERVAL '7 days', 55, 60, 70, 62, 72, 84, 58,
        v_start + INTERVAL '7 days', v_start + INTERVAL '6 days 20 hours')
    RETURNING session_seq INTO v_session;

    INSERT INTO simulation_inventory_items (session_seq, item_seq, quantity) VALUES
        (v_session, i_bowl, 1), (v_session, i_water, 1), (v_session, i_dog_food, 0), (v_session, i_toy, 1), (v_session, i_dog_snack, 1);
    INSERT INTO simulation_transactions (session_seq, transaction_type, item_seq, quantity, amount, balance_after, description, reg_dttm) VALUES
        (v_session, 'PURCHASE', i_bowl, 1, 12000, 138000, '스테인리스 밥그릇 1개', v_start + INTERVAL '10 minutes'),
        (v_session, 'PURCHASE', i_water, 1, 9000, 129000, '도자기 물그릇 1개', v_start + INTERVAL '11 minutes'),
        (v_session, 'PURCHASE', i_dog_food, 5, 40000, 89000, '강아지 건식 사료 1kg 5개', v_start + INTERVAL '12 minutes'),
        (v_session, 'PURCHASE', i_toy, 1, 6000, 83000, '터그 공 장난감 1개', v_start + INTERVAL '2 days'),
        (v_session, 'PURCHASE', i_dog_snack, 3, 15000, 68000, '강아지 육포 간식 3개', v_start + INTERVAL '3 days');
    INSERT INTO simulation_action_logs (session_seq, action_type, action_code, stats_after_jsonb, reg_dttm) VALUES
        (v_session, 'SYSTEM', 'SESSION_START', '{"satiety":70,"hydration":70,"cleanliness":80,"bond":30,"calmness":50,"health":90,"energy":60}', v_start);
    INSERT INTO simulation_action_logs (session_seq, action_type, action_code, item_seq, stat_delta_jsonb, stats_after_jsonb, reg_dttm) VALUES
        (v_session, 'CARE', 'FEED', i_dog_food, '{"satiety":30,"bond":1}', '{"satiety":96,"hydration":65,"cleanliness":78,"bond":31,"calmness":48,"health":90,"energy":57}', v_start + INTERVAL '1 hour'),
        (v_session, 'CARE', 'WATER', NULL, '{"hydration":30}', '{"satiety":92,"hydration":90,"cleanliness":76,"bond":31,"calmness":46,"health":90,"energy":54}', v_start + INTERVAL '2 hours');

    -- 아픔 이벤트를 병원으로 해결(로그 INSERT → occurrence UPDATE 순서, 상호 FK)
    INSERT INTO simulation_event_occurrences (session_seq, scenario_seq, status, occurred_dttm, expires_dttm)
    VALUES (v_session, s_vet, 'PENDING', v_start + INTERVAL '4 days', v_start + INTERVAL '4 days 6 hours')
    RETURNING occurrence_seq INTO v_occ;
    INSERT INTO simulation_action_logs (session_seq, action_type, action_code, occurrence_seq, stat_delta_jsonb, stats_after_jsonb, reg_dttm)
    VALUES (v_session, 'CARE', 'VET_VISIT', v_occ, '{"health":30,"calmness":10}',
        '{"satiety":40,"hydration":35,"cleanliness":60,"bond":35,"calmness":40,"health":68,"energy":40}', v_start + INTERVAL '4 days 1 hour')
    RETURNING action_log_seq INTO v_log;
    UPDATE simulation_event_occurrences SET status = 'RESOLVED', resolved_dttm = v_start + INTERVAL '4 days 1 hour', resolved_action_log_seq = v_log
    WHERE occurrence_seq = v_occ;
    INSERT INTO simulation_transactions (session_seq, transaction_type, occurrence_seq, quantity, amount, balance_after, description, reg_dttm)
    VALUES (v_session, 'EVENT', v_occ, 1, 30000, 38000, '축 처져 있어요 진료비', v_start + INTERVAL '4 days 1 hour');

    -- 산책 1회(짖기 이벤트는 방치되어 만료)
    INSERT INTO simulation_walks (session_seq, status, started_dttm, last_heartbeat_dttm, ended_dttm)
    VALUES (v_session, 'ENDED', v_start + INTERVAL '5 days', v_start + INTERVAL '5 days 34 minutes', v_start + INTERVAL '5 days 35 minutes')
    RETURNING walk_seq INTO v_walk;
    INSERT INTO simulation_event_occurrences (session_seq, scenario_seq, walk_seq, status, occurred_dttm, expires_dttm)
    VALUES (v_session, s_bark, v_walk, 'EXPIRED', v_start + INTERVAL '5 days 10 minutes', v_start + INTERVAL '5 days 15 minutes')
    RETURNING occurrence_seq INTO v_occ;
    INSERT INTO simulation_action_logs (session_seq, action_type, action_code, walk_seq, occurrence_seq, stat_delta_jsonb, stats_after_jsonb, reg_dttm) VALUES
        (v_session, 'WALK', 'WALK_START', v_walk, NULL, NULL, '{"satiety":60,"hydration":60,"cleanliness":70,"bond":50,"calmness":55,"health":80,"energy":40}', v_start + INTERVAL '5 days'),
        (v_session, 'EVENT', 'EVENT_EXPIRE', NULL, v_occ, '{"calmness":-8}', '{"satiety":60,"hydration":60,"cleanliness":70,"bond":50,"calmness":47,"health":80,"energy":40}', v_start + INTERVAL '5 days 15 minutes'),
        (v_session, 'WALK', 'WALK_END', v_walk, NULL, '{"energy":25,"calmness":12,"bond":4,"satiety":-6,"hydration":-8}', '{"satiety":54,"hydration":52,"cleanliness":70,"bond":54,"calmness":59,"health":80,"energy":65}', v_start + INTERVAL '5 days 35 minutes'),
        (v_session, 'SYSTEM', 'SESSION_END', NULL, NULL, NULL, '{"satiety":55,"hydration":60,"cleanliness":70,"bond":62,"calmness":72,"health":84,"energy":58}', v_start + INTERVAL '7 days');

    INSERT INTO simulation_reports (session_seq, expected_cost, total_spent, spent_by_category_jsonb, daily_spent_jsonb, final_stats_jsonb,
        action_count, event_count, event_resolved_count, walk_count, summary)
    VALUES (v_session, 150000, 112000,
        '{"FOOD_BOWL":12000,"WATER_BOWL":9000,"LITTER_BOX":0,"TOY":6000,"FOOD":40000,"SNACK":15000,"LITTER":0,"SHAMPOO":0,"MEDICINE":0,"EVENT":30000}',
        '{"1":61000,"2":0,"3":6000,"4":15000,"5":30000,"6":0,"7":0}',
        '{"satiety":55,"hydration":60,"cleanliness":70,"bond":62,"calmness":72,"health":84,"energy":58}',
        3, 2, 1, 1,
        '초코와 일주일을 끝까지 함께했어요. 예상 비용보다 38,000원 덜 썼어요. 사료 지출이 가장 컸습니다. 돌발 상황 2번 중 1번을 잘 해결했어요.');

    -- ===== 2) 회원 131: 고양이, 돌봄 부족 중단(FAILED) =====
    v_start := now() - INTERVAL '5 days';
    INSERT INTO simulation_sessions (user_seq, breed_seq, animal_name, species_type, personality, status, expected_cost, balance,
        started_dttm, ends_dttm, ended_dttm, satiety, hydration, cleanliness, bond, calmness, health, energy,
        stats_calculated_dttm, last_action_dttm)
    VALUES (131, v_cat_breed, '나비', 'CAT', v_cat_personality, 'FAILED', 80000, 80000 - 25000 - 18000 - 7000,
        v_start, v_start + INTERVAL '7 days', v_start + INTERVAL '3 days 2 hours', 0, 0, 12, 20, 10, 0, 5,
        v_start + INTERVAL '3 days 2 hours', v_start + INTERVAL '6 hours')
    RETURNING session_seq INTO v_session;

    INSERT INTO simulation_inventory_items (session_seq, item_seq, quantity) VALUES
        (v_session, i_litter_box, 1), (v_session, i_cat_food, 2), (v_session, i_litter, 0);
    INSERT INTO simulation_transactions (session_seq, transaction_type, item_seq, quantity, amount, balance_after, description, reg_dttm) VALUES
        (v_session, 'PURCHASE', i_litter_box, 1, 25000, 55000, '고양이 화장실 1개', v_start + INTERVAL '5 minutes'),
        (v_session, 'PURCHASE', i_cat_food, 2, 18000, 37000, '고양이 건식 사료 1kg 2개', v_start + INTERVAL '6 minutes'),
        (v_session, 'PURCHASE', i_litter, 1, 7000, 30000, '벤토나이트 모래 5kg 1개', v_start + INTERVAL '7 minutes');
    INSERT INTO simulation_action_logs (session_seq, action_type, action_code, item_seq, stat_delta_jsonb, stats_after_jsonb, reg_dttm) VALUES
        (v_session, 'SYSTEM', 'SESSION_START', NULL, NULL, '{"satiety":70,"hydration":70,"cleanliness":80,"bond":30,"calmness":50,"health":90,"energy":60}', v_start),
        (v_session, 'CARE', 'CLEAN_LITTER', i_litter, '{"cleanliness":20}', '{"satiety":50,"hydration":45,"cleanliness":88,"bond":28,"calmness":42,"health":90,"energy":45}', v_start + INTERVAL '6 hours');
    INSERT INTO simulation_event_occurrences (session_seq, scenario_seq, status, occurred_dttm, expires_dttm)
    VALUES (v_session, s_litter, 'EXPIRED', v_start + INTERVAL '1 day', v_start + INTERVAL '1 day 6 hours')
    RETURNING occurrence_seq INTO v_occ;
    INSERT INTO simulation_action_logs (session_seq, action_type, action_code, occurrence_seq, stat_delta_jsonb, stats_after_jsonb, reg_dttm) VALUES
        (v_session, 'EVENT', 'EVENT_EXPIRE', v_occ, '{"cleanliness":-15,"calmness":-10}', '{"satiety":10,"hydration":5,"cleanliness":40,"bond":24,"calmness":20,"health":60,"energy":20}', v_start + INTERVAL '1 day 6 hours'),
        (v_session, 'SYSTEM', 'SESSION_END', NULL, NULL, '{"satiety":0,"hydration":0,"cleanliness":12,"bond":20,"calmness":10,"health":0,"energy":5}', v_start + INTERVAL '3 days 2 hours');
    INSERT INTO simulation_reports (session_seq, expected_cost, total_spent, spent_by_category_jsonb, daily_spent_jsonb, final_stats_jsonb,
        action_count, event_count, event_resolved_count, walk_count, summary)
    VALUES (v_session, 80000, 50000,
        '{"FOOD_BOWL":0,"WATER_BOWL":0,"LITTER_BOX":25000,"TOY":0,"FOOD":18000,"SNACK":0,"LITTER":7000,"SHAMPOO":0,"MEDICINE":0,"EVENT":0}',
        '{"1":50000,"2":0,"3":0,"4":0,"5":0,"6":0,"7":0}',
        '{"satiety":0,"hydration":0,"cleanliness":12,"bond":20,"calmness":10,"health":0,"energy":5}',
        1, 1, 0, 0,
        '돌봄이 부족해 나비와의 모의 입양이 중단되었어요. 예상 비용보다 30,000원 덜 썼어요. 화장실 지출이 가장 컸습니다. 돌발 상황 1번 중 0번을 잘 해결했어요. 마지막에는 포만감 관리가 더 필요했어요.');

    -- ===== 3) 회원 132: 개, 진행 중(시작 하루 전, 밥그릇만 보유) =====
    v_start := now() - INTERVAL '1 day';
    INSERT INTO simulation_sessions (user_seq, breed_seq, animal_name, species_type, personality, status, expected_cost, balance,
        started_dttm, ends_dttm, satiety, hydration, cleanliness, bond, calmness, health, energy, stats_calculated_dttm)
    VALUES (132, v_dog_breed, '보리', 'DOG', v_dog_personality, 'IN_PROGRESS', 120000, 120000 - 12000,
        v_start, v_start + INTERVAL '7 days', 70, 70, 80, 30, 50, 90, 60, v_start)
    RETURNING session_seq INTO v_session;
    INSERT INTO simulation_inventory_items (session_seq, item_seq, quantity) VALUES (v_session, i_bowl, 1);
    INSERT INTO simulation_transactions (session_seq, transaction_type, item_seq, quantity, amount, balance_after, description, reg_dttm)
    VALUES (v_session, 'PURCHASE', i_bowl, 1, 12000, 108000, '스테인리스 밥그릇 1개', v_start + INTERVAL '3 minutes');
    INSERT INTO simulation_action_logs (session_seq, action_type, action_code, stats_after_jsonb, reg_dttm)
    VALUES (v_session, 'SYSTEM', 'SESSION_START', '{"satiety":70,"hydration":70,"cleanliness":80,"bond":30,"calmness":50,"health":90,"energy":60}', v_start);
END $$;
