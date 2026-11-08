-- 모의 입양 마스터 시드(Q412): 펫샵 물품 9분류(36), 이벤트 시나리오(41). 관리 API 없음
-- effects 키는 스탯 7종(Q485), 양수가 좋은 방향. 수치는 DB 명세 §3·API 18.6 예시 기반 [가안]
-- image_key는 공개 사진 접두어(images/, Q443) 아래 시드 키. 실제 객체 업로드는 별도

INSERT INTO simulation_shop_items
    (item_name, category, is_consumable, price, description, image_key, species_scope, effects_jsonb, display_order)
VALUES
    ('스테인리스 밥그릇', 'FOOD_BOWL', FALSE, 12000, '한 번 사면 계속 쓰는 기구입니다. 밥 주기에 필요해요.', 'images/seed/simulation/items/food-bowl.png', 'ALL', NULL, 10),
    ('도자기 물그릇', 'WATER_BOWL', FALSE, 9000, '한 번 사면 계속 쓰는 기구입니다. 물은 무료예요.', 'images/seed/simulation/items/water-bowl.png', 'ALL', NULL, 20),
    ('고양이 화장실', 'LITTER_BOX', FALSE, 25000, '한 번 사면 계속 쓰는 기구입니다. 모래와 함께 써요.', 'images/seed/simulation/items/litter-box.png', 'CAT', NULL, 30),
    ('터그 공 장난감', 'TOY', FALSE, 6000, '함께 놀아 주면 친밀도와 안정감이 올라가요.', 'images/seed/simulation/items/toy-ball.png', 'DOG', '{"bond": 5, "calmness": 5, "energy": -5}', 40),
    ('깃털 낚싯대', 'TOY', FALSE, 7000, '함께 놀아 주면 친밀도와 안정감이 올라가요.', 'images/seed/simulation/items/toy-feather.png', 'CAT', '{"bond": 5, "calmness": 5, "energy": -5}', 41),
    ('강아지 건식 사료 1kg', 'FOOD', TRUE, 8000, '한 끼 분량씩 소모됩니다.', 'images/seed/simulation/items/dog-food.png', 'DOG', '{"satiety": 30, "bond": 1}', 50),
    ('고양이 건식 사료 1kg', 'FOOD', TRUE, 9000, '한 끼 분량씩 소모됩니다.', 'images/seed/simulation/items/cat-food.png', 'CAT', '{"satiety": 30, "bond": 1}', 51),
    ('습식 캔', 'FOOD', TRUE, 3000, '수분도 함께 채워 주는 한 끼예요.', 'images/seed/simulation/items/wet-can.png', 'ALL', '{"satiety": 20, "hydration": 10, "bond": 2}', 52),
    ('강아지 육포 간식', 'SNACK', TRUE, 5000, '산책 중에도 줄 수 있는 간식이에요.', 'images/seed/simulation/items/dog-snack.png', 'DOG', '{"satiety": 10, "bond": 3, "calmness": 5}', 60),
    ('고양이 짜 먹는 간식', 'SNACK', TRUE, 5000, '좋아하는 간식으로 친해져 보세요.', 'images/seed/simulation/items/cat-snack.png', 'CAT', '{"satiety": 10, "bond": 3, "calmness": 5}', 61),
    ('벤토나이트 모래 5kg', 'LITTER', TRUE, 7000, '화장실 청소 1회에 하나씩 소모됩니다.', 'images/seed/simulation/items/litter.png', 'CAT', '{"cleanliness": 40}', 70),
    ('저자극 샴푸', 'SHAMPOO', TRUE, 8000, '목욕 1회에 하나씩 소모됩니다.', 'images/seed/simulation/items/shampoo.png', 'ALL', '{"cleanliness": 40, "calmness": -5}', 80),
    ('종합 영양제', 'MEDICINE', TRUE, 10000, '가벼운 증상에 먹이는 약이에요.', 'images/seed/simulation/items/medicine.png', 'ALL', '{"health": 20}', 90);

-- 정답 행동 1개(Q486). HOME 정답은 18.6 돌봄 행동, WALK 정답은 GIVE_WATER/GIVE_SNACK. 비용은 VET_VISIT만
-- RANDOM은 trigger_probability 1.00(가중치 추첨), CONDITION은 조건 충족 시 판정 1회당 확률(Q481)
INSERT INTO simulation_event_scenarios
    (title, description, trigger_context, trigger_type, trigger_condition_jsonb, cooldown_hours, trigger_probability,
     species_scope, personality_scope, weight, resolve_action_code, resolve_cost, resolve_effects_jsonb, result_text,
     neglect_effects_jsonb, neglect_text)
VALUES
    -- ===== HOME · RANDOM =====
    ('밥그릇 앞에서 기다려요', '빈 밥그릇 앞에 앉아 꼬리를 흔들고 있어요. 무엇을 해 줘야 할까요?', 'HOME', 'RANDOM', NULL, NULL, 1.00,
     'ALL', NULL, 3, 'FEED', 0, '{"bond": 3, "calmness": 5}', '맛있게 먹고 만족한 표정이에요.',
     '{"satiety": -10, "calmness": -5}', '밥을 줬어야 해요. 배고픔에 지쳐 잠들었어요.'),
    ('목이 마른 것 같아요', '혀를 내밀고 물그릇 쪽을 자꾸 쳐다봐요.', 'HOME', 'RANDOM', NULL, NULL, 1.00,
     'ALL', NULL, 3, 'WATER', 0, '{"hydration": 10, "bond": 2}', '시원하게 물을 마시고 기운을 차렸어요.',
     '{"hydration": -10, "health": -3}', '물을 줬어야 해요. 목이 말라 힘들어했어요.'),
    ('놀아 달라고 보채요', '장난감을 물고 와서 발 앞에 내려놓았어요.', 'HOME', 'RANDOM', NULL, NULL, 1.00,
     'ALL', NULL, 3, 'PLAY', 0, '{"bond": 8, "calmness": 5}', '신나게 놀고 나서 곁에 붙어 쉬어요.',
     '{"calmness": -10, "bond": -5}', '같이 놀아 줬어야 해요. 심심해서 시무룩해졌어요.'),
    ('온몸이 꼬질꼬질해요', '어디서 뒹굴었는지 털에 먼지가 잔뜩 묻었어요.', 'HOME', 'RANDOM', NULL, NULL, 1.00,
     'ALL', NULL, 2, 'BATHE', 0, '{"cleanliness": 10, "bond": 2}', '목욕을 마치고 보송보송해졌어요.',
     '{"cleanliness": -15, "health": -3}', '목욕을 시켜 줬어야 해요. 피부가 간지러워졌어요.'),
    ('화장실이 지저분해요', '화장실 앞에서 머뭇거리다 돌아서요.', 'HOME', 'RANDOM', NULL, NULL, 1.00,
     'CAT', NULL, 3, 'CLEAN_LITTER', 0, '{"cleanliness": 10, "calmness": 5}', '깨끗한 화장실에 만족했어요.',
     '{"cleanliness": -15, "calmness": -10}', '화장실을 치워 줬어야 해요. 불안해서 숨어 버렸어요.'),
    ('간식 냄새를 맡았어요', '간식 봉지 근처를 맴돌며 눈을 반짝여요.', 'HOME', 'RANDOM', NULL, NULL, 1.00,
     'ALL', NULL, 2, 'SNACK', 0, '{"bond": 5}', '간식을 받고 기분이 아주 좋아졌어요.',
     '{"calmness": -5}', '간식을 줬어야 해요. 실망해서 등을 돌렸어요.'),
    -- ===== HOME · CONDITION (아픔 등, M15) =====
    ('축 처져 있어요', '밥을 잘 안 먹고 구석에서 움직이지 않아요. 어떻게 해줘야 할까요?', 'HOME', 'CONDITION',
     '{"stat": "health", "op": "<", "value": 40}', 24, 0.50,
     'ALL', NULL, 1, 'VET_VISIT', 30000, '{"health": 30, "calmness": 10}', '진단서: 가벼운 장염 소견. 처방식과 휴식을 권장합니다.',
     '{"health": -10}', '병원에 데려갔어야 해요. 건강이 더 나빠졌어요.'),
    ('기침을 해요', '가끔 콜록거리고 콧물이 보여요.', 'HOME', 'CONDITION',
     '{"stat": "health", "op": "<", "value": 60}', 24, 0.20,
     'ALL', NULL, 1, 'MEDICINE', 0, '{"health": 15}', '약을 먹고 기침이 잦아들었어요.',
     '{"health": -5}', '약을 먹였어야 해요. 기침이 심해졌어요.'),
    ('피부를 자꾸 긁어요', '몸 여기저기를 긁느라 잠을 못 자요.', 'HOME', 'CONDITION',
     '{"stat": "cleanliness", "op": "<", "value": 30}', 24, 0.40,
     'ALL', NULL, 1, 'BATHE', 0, '{"health": 5, "calmness": 5}', '목욕 후 가려움이 가라앉았어요.',
     '{"health": -5}', '목욕을 시켜 줬어야 해요. 피부 트러블이 생겼어요.'),
    ('기운이 없어요', '하루 종일 한숨을 쉬며 누워만 있어요.', 'HOME', 'CONDITION',
     '{"stat": "calmness", "op": "<=", "value": 25}', 24, 0.40,
     'ALL', NULL, 1, 'PLAY', 0, '{"calmness": 10, "energy": 5}', '함께 놀고 나서 생기가 돌아왔어요.',
     '{"health": -5}', '함께 놀아 줬어야 해요. 무기력해졌어요.'),
    ('밥을 찾아 헤매요', '집 안을 돌아다니며 먹을 것을 찾아요.', 'HOME', 'CONDITION',
     '{"stat": "satiety", "op": "<", "value": 25}', 24, 0.50,
     'ALL', NULL, 1, 'FEED', 0, '{"health": 5}', '든든히 먹고 안정을 찾았어요.',
     '{"health": -8}', '밥을 줬어야 해요. 영양이 부족해졌어요.'),
    -- ===== WALK · RANDOM (DOG 전용, Q488) =====
    ('다른 개를 보고 짖어요', '건너편 강아지를 보고 목줄을 당기며 짖어요.', 'WALK', 'RANDOM', NULL, NULL, 1.00,
     'DOG', NULL, 2, 'GIVE_SNACK', 0, '{"calmness": 8, "bond": 2}', '간식에 집중하더니 금세 얌전해졌어요.',
     '{"calmness": -8}', '간식으로 주의를 돌려 줬어야 해요. 한참 흥분해 있었어요.'),
    -- AGGRESSIVE 성격의 "산책 중 짖기·싸우기 가중치 2"(M4)는 이 행으로 표현
    ('다른 개에게 달려들어요', '낯선 개에게 으르렁거리며 달려들려고 해요.', 'WALK', 'RANDOM', NULL, NULL, 1.00,
     'DOG', 'AGGRESSIVE', 2, 'GIVE_SNACK', 0, '{"calmness": 10}', '간식으로 진정시키고 무사히 지나갔어요.',
     '{"calmness": -12, "bond": -3}', '간식으로 진정시켜 줬어야 해요. 싸울 뻔했어요.'),
    ('헥헥거리며 빨리 달려요', '신이 나서 앞으로만 달려가요. 숨이 차 보여요.', 'WALK', 'RANDOM', NULL, NULL, 1.00,
     'DOG', NULL, 2, 'GIVE_WATER', 0, '{"hydration": 10, "calmness": 3}', '물을 마시고 숨을 골랐어요.',
     '{"hydration": -10}', '물을 줬어야 해요. 지쳐 버렸어요.'),
    ('냄새를 맡느라 멈췄어요', '풀숲에 코를 박고 움직이지 않아요.', 'WALK', 'RANDOM', NULL, NULL, 1.00,
     'DOG', NULL, 2, 'GIVE_SNACK', 0, '{"bond": 3}', '간식을 보여 주자 다시 따라 걸었어요.',
     '{"calmness": -3}', '간식으로 불러 줬어야 해요. 한참 실랑이했어요.'),
    ('그 자리에 앉아 버렸어요', '길 한가운데 앉아서 꼼짝하지 않아요.', 'WALK', 'RANDOM', NULL, NULL, 1.00,
     'DOG', NULL, 1, 'GIVE_WATER', 0, '{"energy": 5, "hydration": 5}', '물을 마시고 다시 힘을 냈어요.',
     '{"energy": -5}', '물을 줬어야 해요. 더위에 지쳐 있었어요.'),
    ('오줌을 쌌어요', '전봇대 앞에서 볼일을 봤어요.', 'WALK', 'RANDOM', NULL, NULL, 1.00,
     'DOG', NULL, 1, 'GIVE_WATER', 0, '{"hydration": 5}', '물로 흔적을 씻어 내고 물도 마셨어요.',
     '{"hydration": -5}', '물을 줬어야 해요. 목이 말랐어요.');
