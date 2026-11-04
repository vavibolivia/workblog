-- 대문에는 글만 올린다. V14 가 옮겨 둔 옛 공수 표(MM_SHEET) 항목은 저장 때 막혀 대문을 고칠 수 없게 만들 수 있어 지운다.
-- 없는 글을 가리키는 항목도 함께 정리(영구 삭제 때 남던 것).
DELETE FROM front_items WHERE kind = 'MM_SHEET';
DELETE FROM front_items f WHERE f.kind = 'POST' AND NOT EXISTS (SELECT 1 FROM posts p WHERE p.id = f.ref_id);
