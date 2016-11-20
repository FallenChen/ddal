/*
 * Copyright 2016-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hellojavaer.ddr.core.sharding;

import java.util.Map;

/**
 *
 * @author <a href="mailto:hellojavaer@gmail.com">zoukaiming[邹凯明]</a>,created on 15/11/2016.
 */
public class ShardingRouteParser {

    private SqlParser      sqlParser;
    private ShardingRouter shardingRouter;

    public ShardingRouter getShardingRouter() {
        return shardingRouter;
    }

    public void setShardingRouter(ShardingRouter shardingRouter) {
        this.shardingRouter = shardingRouter;
    }

    public SqlParser getSqlParser() {
        return sqlParser;
    }

    public void setSqlParser(SqlParser sqlParser) {
        this.sqlParser = sqlParser;
    }

    public String parse(String sql, Map<Integer, Object> jdbcParam) {
        if (sqlParser == null) {
            sqlParser = new JSQLSqlParser(jdbcParam);
        }
        if (shardingRouter == null) {
            shardingRouter = new SimpleShardingRouter();
        }
        sqlParser.setShardingRouter(shardingRouter);
        return sqlParser.parse(sql);
    }
}
