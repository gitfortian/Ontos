-- M5/W1 真库语义验证（session_state：占用 / 投影裁剪 / 澄清消费 / 键隔离）
DELETE FROM session_state WHERE tenant_id='demo' AND user_id='sql-verify';
INSERT INTO session_state (tenant_id,user_id,session_id) VALUES ('demo','sql-verify','sv1');

\echo '--- 占用 turn 0（应返回 1）---'
UPDATE session_state SET turn_index=turn_index+1 WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1' AND turn_index=0 RETURNING turn_index;

\echo '--- 重复占用 turn 0（应 UPDATE 0）---'
UPDATE session_state SET turn_index=turn_index+1 WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1' AND turn_index=0;

\echo '--- 投影 x6（recent_turns 深度应保持 5）---'
UPDATE session_state SET resolved_entities='{"m":"metric.gmv","iter":1}'::jsonb, recent_turns = (SELECT coalesce(jsonb_agg(entry),'[]'::jsonb) FROM (SELECT entry FROM jsonb_array_elements(recent_turns) WITH ORDINALITY AS t(entry,ord) ORDER BY ord DESC LIMIT 4) latest) || cast('{"turn":1}' as jsonb), last_terminal='query' WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1';
UPDATE session_state SET resolved_entities='{"m":"metric.gmv","iter":2}'::jsonb, recent_turns = (SELECT coalesce(jsonb_agg(entry),'[]'::jsonb) FROM (SELECT entry FROM jsonb_array_elements(recent_turns) WITH ORDINALITY AS t(entry,ord) ORDER BY ord DESC LIMIT 4) latest) || cast('{"turn":2}' as jsonb), last_terminal='query' WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1';
UPDATE session_state SET resolved_entities='{"m":"metric.gmv","iter":3}'::jsonb, recent_turns = (SELECT coalesce(jsonb_agg(entry),'[]'::jsonb) FROM (SELECT entry FROM jsonb_array_elements(recent_turns) WITH ORDINALITY AS t(entry,ord) ORDER BY ord DESC LIMIT 4) latest) || cast('{"turn":3}' as jsonb), last_terminal='query' WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1';
UPDATE session_state SET resolved_entities='{"m":"metric.gmv","iter":4}'::jsonb, recent_turns = (SELECT coalesce(jsonb_agg(entry),'[]'::jsonb) FROM (SELECT entry FROM jsonb_array_elements(recent_turns) WITH ORDINALITY AS t(entry,ord) ORDER BY ord DESC LIMIT 4) latest) || cast('{"turn":4}' as jsonb), last_terminal='query' WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1';
UPDATE session_state SET resolved_entities='{"m":"metric.gmv","iter":5}'::jsonb, recent_turns = (SELECT coalesce(jsonb_agg(entry),'[]'::jsonb) FROM (SELECT entry FROM jsonb_array_elements(recent_turns) WITH ORDINALITY AS t(entry,ord) ORDER BY ord DESC LIMIT 4) latest) || cast('{"turn":5}' as jsonb), last_terminal='query' WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1';
UPDATE session_state SET resolved_entities='{"m":"metric.gmv","iter":6}'::jsonb, recent_turns = (SELECT coalesce(jsonb_agg(entry),'[]'::jsonb) FROM (SELECT entry FROM jsonb_array_elements(recent_turns) WITH ORDINALITY AS t(entry,ord) ORDER BY ord DESC LIMIT 4) latest) || cast('{"turn":6}' as jsonb), last_terminal='query' WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1';

\echo '--- 深度（应 5）+ 最后投影 iter（应 6）+ 终态 ---'
SELECT jsonb_array_length(recent_turns) AS depth, resolved_entities->>'iter' AS last_iter, last_terminal FROM session_state WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1';

\echo '--- 澄清候选写入 ---'
UPDATE session_state SET pending_clarification='{"ambiguity":"口径歧义","candidates":[{"candidateId":"cand-1","label":"GMV 总额"},{"candidateId":"cand-2","label":"已支付 GMV"}],"defaultSuggestion":"GMV 总额"}'::jsonb WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1';

\echo '--- 有效选择消费（应 UPDATE 1）---'
UPDATE session_state SET pending_clarification=NULL WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1' AND pending_clarification IS NOT NULL AND pending_clarification->'candidates' @> to_jsonb(jsonb_build_array(jsonb_build_object('candidateId','cand-2')));

\echo '--- 伪造选择消费（应 UPDATE 0——pending 已被上一步清空）---'
UPDATE session_state SET pending_clarification='{"candidates":[{"candidateId":"cand-1"}]}'::jsonb WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1';
UPDATE session_state SET pending_clarification=NULL WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv1' AND pending_clarification IS NOT NULL AND pending_clarification->'candidates' @> to_jsonb(jsonb_build_array(jsonb_build_object('candidateId','forged')));

\echo '--- 键隔离（不同 session 无行）---'
SELECT count(*) AS other_session_rows FROM session_state WHERE tenant_id='demo' AND user_id='sql-verify' AND session_id='sv2';

\echo '--- 清理 ---'
DELETE FROM session_state WHERE tenant_id='demo' AND user_id='sql-verify';
