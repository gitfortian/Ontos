-- 只在首次初始化数据目录时执行：创建最小权限角色、Query DS 与扩展。
-- 生产必须替换为外部密钥管理与独立只读实例，禁止复用这里的弱口令。

CREATE ROLE ontos_app LOGIN PASSWORD 'ontos_app';
CREATE ROLE ontos_query_ro LOGIN PASSWORD 'ontos_query_ro';

CREATE DATABASE ontos_queryds OWNER ontos_app;

-- Flyway 以 ontos_app 连接 System DB 建表
GRANT USAGE, CREATE ON SCHEMA public TO ontos_app;
GRANT CONNECT ON DATABASE ontos_queryds TO ontos_query_ro;

\connect ontos_system
CREATE EXTENSION IF NOT EXISTS pgcrypto;
GRANT USAGE, CREATE ON SCHEMA public TO ontos_app;
