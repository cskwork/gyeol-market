-- Fictional catalog matching frontend/src/demo/fixtures.ts. No reviews are invented.

INSERT INTO brand (id, name, slug, logo_url) VALUES
    (1, '새봄담', 'saebomdam', NULL),
    (2, '도담랩', 'dodamlab', NULL),
    (3, '더마하랑', 'dermaharang', NULL),
    (4, '벨로아', 'veloa', NULL),
    (5, '힐로담', 'hilodam', NULL),
    (6, '솔향공방', 'solhyang', NULL),
    (7, '그린마루', 'greenmaru', NULL);

INSERT INTO category (id, parent_id, name, slug, sort_order) VALUES
    (1, NULL, '스킨케어', 'skincare', 1),
    (2, NULL, '메이크업', 'makeup', 2),
    (3, NULL, '헤어/바디', 'hair-body', 3);

INSERT INTO product (id, brand_id, name, description, base_price, sale_price, created_order) VALUES
    (1, 1, '키즈 매일 선크림 SPF50+ PA++++', '어린이 피부에 안전한 무기자차 선크림으로, 물놀이나 수영 후에도 씻어낼 필요가 없습니다.', 25000, 20000, 1),
    (2, 2, '새벽이슬 수분 토너 500ml', '건조한 피부에 산뜻하게 흡수되는 대용량 수분 토너입니다.', 28000, 22400, 2),
    (3, 3, '카밍 시카 진정 크림 70ml', '민감한 피부를 촉촉하게 진정시키는 데일리 수분 크림입니다.', 36000, 25200, 3),
    (4, 7, '티트리 카밍 트러블 세럼 30ml', '티트리와 시카 성분으로 번들거림 없이 케어하는 집중 세럼입니다.', 24000, 16800, 4),
    (5, 1, '약산성 버블 클렌징 폼 150ml', '풍성한 버블로 피부 장벽 부담을 줄인 약산성 클렌징 폼입니다.', 15000, 9900, 5),
    (6, 5, '콜라겐 탄력 시트 마스크팩 10매', '피부 탄력과 윤기를 위한 에센스 듬뿍 시트 마스크 세트입니다.', 22000, 14900, 6),
    (7, 4, '컬링 픽서 워터프루프 마스카라 블랙', '번짐 없이 또렷한 속눈썹을 연출하는 워터프루프 마스카라입니다.', 18000, 12600, 7),
    (8, 4, '소프트 매트 립 틴트 로즈', '가볍게 밀착되는 로즈 컬러의 소프트 블러 립 틴트입니다.', 16000, 11200, 8),
    (9, 4, '데일리 밀착 쿠션 파운데이션 21N', '얇게 밀착되면서 자연스러운 커버를 돕는 데일리 쿠션입니다.', 32000, 25600, 9),
    (10, 6, '로즈마리 두피 쿨링 샴푸 500ml', '두피를 산뜻하게 씻어내는 로즈마리 쿨링 샴푸입니다.', 21000, 16800, 10),
    (11, 6, '퍼퓸 바디워시 피그 480ml', '은은한 무화과 향으로 샤워 후 잔향을 남기는 바디워시입니다.', 19000, 15200, 11),
    (12, 1, '시어버터 핸드크림 3종 세트', '휴대하기 좋은 보습 핸드크림 3종 기획 세트입니다.', 12000, 8900, 12),
    (13, 3, '남성 올인원 플루이드 150ml', '스킨과 로션 단계를 한 번에 끝내는 산뜻한 남성 올인원입니다.', 26000, 19500, 13);

INSERT INTO product_category (product_id, category_id) VALUES
    (1, 1),
    (1, 2),
    (1, 3),
    (2, 1),
    (3, 1),
    (4, 1),
    (5, 1),
    (6, 1),
    (7, 2),
    (8, 2),
    (9, 2),
    (10, 3),
    (11, 3),
    (12, 3),
    (13, 1);

INSERT INTO product_option (id, product_id, name, price, status, available_quantity, sort_order) VALUES
    (1, 1, '50ml', 0, 'ON_SALE', NULL, 1),
    (2, 1, '100ml', 5000, 'ON_SALE', NULL, 2),
    (3, 2, '기본', 0, 'ON_SALE', NULL, 1),
    (4, 3, '기본', 0, 'ON_SALE', NULL, 1),
    (5, 4, '기본', 0, 'ON_SALE', NULL, 1),
    (6, 5, '기본', 0, 'ON_SALE', NULL, 1),
    (7, 6, '기본', 0, 'ON_SALE', NULL, 1),
    (8, 7, '기본', 0, 'ON_SALE', NULL, 1),
    (9, 8, '기본', 0, 'ON_SALE', NULL, 1),
    (10, 9, '기본', 0, 'ON_SALE', NULL, 1),
    (11, 10, '기본', 0, 'ON_SALE', NULL, 1),
    (12, 11, '기본', 0, 'ON_SALE', NULL, 1),
    (13, 12, '기본', 0, 'ON_SALE', NULL, 1),
    (14, 13, '기본', 0, 'ON_SALE', NULL, 1);

INSERT INTO product_image (id, product_id, url, sort_order, is_thumbnail) VALUES
    (10, 1, '/images/products/saebomdam-kids-suncream-spf50-thumb.png', 1, true),
    (11, 1, '/images/products/saebomdam-kids-suncream-detail-1.png', 2, false),
    (12, 1, '/images/products/saebomdam-kids-suncream-detail-2.png', 3, false),
    (20, 2, '/images/products/dodamlab-dew-toner-500ml.png', 1, true),
    (30, 3, '/images/products/dermaharang-calming-cica-cream-70ml.png', 1, true),
    (40, 4, '/images/products/greenmaru-teatree-calming-serum-30ml.png', 1, true),
    (50, 5, '/images/products/saebomdam-low-ph-bubble-cleansing-foam-150ml.png', 1, true),
    (60, 6, '/images/products/hilodam-collagen-sheet-mask-10ea.png', 1, true),
    (70, 7, '/images/products/veloa-curl-fixer-waterproof-mascara-black.png', 1, true),
    (80, 8, '/images/products/veloa-soft-matte-lip-tint-rose.png', 1, true),
    (90, 9, '/images/products/veloa-daily-fit-cushion-foundation-21n.png', 1, true),
    (100, 10, '/images/products/solhyang-rosemary-scalp-cooling-shampoo-500ml.png', 1, true),
    (110, 11, '/images/products/solhyang-perfume-bodywash-fig-480ml.png', 1, true),
    (120, 12, '/images/products/saebomdam-sheabutter-handcream-3set.png', 1, true),
    (130, 13, '/images/products/dermaharang-men-all-in-one-fluid-150ml.png', 1, true);
