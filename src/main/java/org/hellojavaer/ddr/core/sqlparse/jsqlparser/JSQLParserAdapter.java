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
package org.hellojavaer.ddr.core.sqlparse.jsqlparser;

import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.hellojavaer.ddr.core.datasource.exception.CrossingPreparedStatementException;
import org.hellojavaer.ddr.core.datasource.jdbc.SQLParsedResult;
import org.hellojavaer.ddr.core.shard.*;
import org.hellojavaer.ddr.core.sqlparse.exception.*;
import org.hellojavaer.ddr.core.utils.DDRJSONUtils;
import org.hellojavaer.ddr.core.utils.DDRStringUtils;
import org.hellojavaer.ddr.core.utils.DDRToStringBuilder;
import org.hellojavaer.ddr.jsqlparser.utils.JSQLBaseVisitor;
import org.hellojavaer.ddr.jsqlparser.utils.ZZJSqlParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 *
 * @author <a href="mailto:hellojavaer@gmail.com">Kaiming Zou</a>,created on 12/11/2016.
 */
public class JSQLParserAdapter extends JSQLBaseVisitor {

    private Logger              logger       = LoggerFactory.getLogger(this.getClass());

    private String              sql;
    private Map<Object, Object> jdbcParam;
    private ShardRouter         shardRouter;
    private Statement           statement;
    private Set<String>         schemas      = new HashSet<>();
    private List<TableWrapper>  routedTables = new ArrayList<TableWrapper>();

    public JSQLParserAdapter(String sql, Map<Object, Object> jdbcParam, ShardRouter shardRouter) {
        this.sql = sql;
        this.jdbcParam = jdbcParam;
        this.shardRouter = shardRouter;
        try {
            this.statement = ZZJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new DDRSQLParseException(e);
        }
        if (statement instanceof Select //
            || statement instanceof Update//
            || statement instanceof Insert//
            || statement instanceof Delete) {
            // ok
        } else {
            throw new UnsupportedSQLExpressionException(
                                                        "Sql ["
                                                                + sql
                                                                + "] is not supported in sharded sql. Only support 'select' 'insert' 'update' and 'delete' sql statement");
        }
    }

    public SQLParsedResult parse() {
        try {
            ConverterContext converterContext = new ConverterContext();
            context.set(converterContext);
            statement.accept(this);
            String targetSql = statement.toString();
            SQLParsedResult parsedResult = new SQLParsedResult();
            parsedResult.setSql(targetSql);
            parsedResult.setSchemas(schemas);
            parsedResult.setParserState(new SQLParsedResult.ParserState() {

                @Override
                public void validJdbcParam(Map<Object, Object> jdbcParam) {
                    if (jdbcParam != null && !jdbcParam.isEmpty()) {
                        for (TableWrapper tableWrapper : routedTables) {
                            RouteInfo info = shardRouter.route(JSQLParserAdapter.this.getContext().getTableRouterContext(),
                                                               tableWrapper.getScName(), tableWrapper.getTbName(), null);
                            if (!equalsIgnoreCase(info.getScName(), tableWrapper.getTable().getSchemaName())
                                || !equalsIgnoreCase(info.getTbName(), tableWrapper.getTable().getName())) {
                                throw new CrossingPreparedStatementException("Source sql is [" + sql
                                                                             + "], converted table information is '"
                                                                             + DDRJSONUtils.toJSONString(routedTables)
                                                                             + "' and jdbc parameter is '"
                                                                             + DDRJSONUtils.toJSONString(jdbcParam)
                                                                             + "'");
                            }
                        }
                    }
                }
            });
            return parsedResult;
        } finally {
            context.remove();
        }
    }

    /**
     * 该方法处理以下两种情况
     * 1.sql中分表没有指定sdValue
     * 2.禁用sql路由的情况
     * 
     * 这两种情况下最后都是通过ShardRouteContext进行二次路由
     */
    private void afterVisit() {
        StackContext context = this.getContext().getStack().pop();
        for (Map.Entry<String, TableWrapper> entry : context.entrySet()) {
            TableWrapper tableWrapper = entry.getValue();
            if (!entry.getValue().isConverted()) {// 回溯没有被转换的表
                // 首先通过标准规则转换
                RouteInfo shardRouterRouteInfo = this.shardRouter.route(this.getContext().getTableRouterContext(),
                                                                        tableWrapper.getScName(),
                                                                        tableWrapper.getTbName(), null);
                if (shardRouterRouteInfo != null) {
                    route0(tableWrapper, shardRouterRouteInfo);
                } else {// 如果没有转换信息则通过ShardRouteContext 转换
                    Object routeInfo = ShardRouteContext.getRouteInfo(tableWrapper.getScName(),
                                                                      tableWrapper.getTbName());
                    if (routeInfo == null) {
                        if (tableWrapper.getSdKey() != null) {// 禁用sql路由后但未在ShardRouteContext中设置路由
                            throw new RouteInfoNotFoundException(
                                                                 "Disabled sql ["
                                                                         + sql
                                                                         + "] routing but not set route information by 'ShardRouteContext'. detail information is "
                                                                         + tableWrapper);
                        } else {// 没有配置分配值
                            StringBuilder sb = new StringBuilder();
                            sb.append("No shard value was configured for shard key '");
                            if (tableWrapper.getTable().getAlias() != null) {
                                sb.append(tableWrapper.getTable().getAlias().getName());
                            } else {
                                sb.append(tableWrapper.getTable().getName());
                            }
                            sb.append('.');
                            sb.append(tableWrapper.getRouteInfo().getSdKey());
                            sb.append("' in sql [");
                            sb.append(sql);
                            sb.append("].");
                            sb.append(" Detail information is ");
                            sb.append(tableWrapper.toString());
                            throw new RouteInfoNotFoundException(sb.toString());
                        }
                    } else if (routeInfo instanceof RouteInfo) {
                        route0(tableWrapper, (RouteInfo) routeInfo);
                    } else {
                        RouteInfo routeInfo1 = this.shardRouter.route(this.getContext().getTableRouterContext(),
                                                                      tableWrapper.getScName(),
                                                                      tableWrapper.getTbName(), routeInfo);
                        route0(tableWrapper, routeInfo1);
                    }
                }
            }
        }
    }

    private void route0(TableWrapper tableWrapper, RouteInfo routeInfo) {
        if (tableWrapper.isConverted()) {// 多重路由
            if (!equalsIgnoreCase(routeInfo.getScName(), tableWrapper.getTable().getSchemaName())
                || !equalsIgnoreCase(routeInfo.getTbName(), tableWrapper.getTable().getName())) {
                throw new ConflictingRouteException(
                                                    "Shard skey '"
                                                            + tableWrapper.getSdKey()
                                                            + "' has multiple values to route its table name, but route results are conflicting, conflicting information is [scName:"
                                                            + routeInfo.getScName() + ",tbName:"
                                                            + routeInfo.getTbName() + "]<->[scName:"
                                                            + tableWrapper.getScName() + "," + tableWrapper.getTbName()
                                                            + "] , source sql is [" + sql + "]");
            }
        } else {
            tableWrapper.getTable().setSchemaName(routeInfo.getScName());
            tableWrapper.getTable().setName(routeInfo.getTbName());
            tableWrapper.setConverted(true);
            schemas.add(routeInfo.getScName());
            routedTables.add(tableWrapper);
        }
    }

    @Override
    public void visit(Insert insert) {
        this.getContext().getStack().push(new StackContext());
        RouteConfig routeInfo = shardRouter.getRouteConfig(this.getContext().getTableRouterContext(),
                                                           insert.getTable().getSchemaName(),
                                                           insert.getTable().getName());
        if (routeInfo != null) {
            addRoutedTableIntoContext(insert.getTable(), routeInfo, false);
            List<Column> columns = insert.getColumns();
            if (columns != null) {
                ExpressionList expressionList = (ExpressionList) insert.getItemsList();
                List<Expression> valueList = expressionList.getExpressions();
                for (int i = 0; i < columns.size(); i++) {
                    Column column = columns.get(i);
                    TableWrapper tableWrapper = getTableFromContext(column);
                    if (tableWrapper != null) {
                        Expression expression = valueList.get(i);
                        routeTable(tableWrapper, column, expression);
                    }
                }
            }
        }
        super.visit(insert);
        afterVisit();
    }

    @Override
    public void visit(Delete delete) {
        this.getContext().getStack().push(new StackContext());
        RouteConfig routeInfo = shardRouter.getRouteConfig(this.getContext().getTableRouterContext(),
                                                           delete.getTable().getSchemaName(),
                                                           delete.getTable().getName());
        if (routeInfo != null) {
            addRoutedTableIntoContext(delete.getTable(), routeInfo, false);
        }
        super.visit(delete);
        afterVisit();
    }

    @Override
    public void visit(Update update) {
        this.getContext().getStack().push(new StackContext());
        for (Table table : update.getTables()) {
            RouteConfig routeInfo = shardRouter.getRouteConfig(this.getContext().getTableRouterContext(),
                                                               table.getSchemaName(), table.getName());
            if (routeInfo != null) {
                addRoutedTableIntoContext(table, routeInfo, true);
            }
        }
        super.visit(update);
        afterVisit();
    }

    @Override
    public void visit(Select select) {
        this.getContext().getStack().push(new StackContext());
        super.visit(select);
        afterVisit();
    }

    @Override
    public void visit(IsNullExpression isNullExpression) {
        Column column = (Column) isNullExpression.getLeftExpression();
        if (getTableFromContext(column) != null) {
            throw new UnsupportedSQLExpressionException(
                                                        "Shard key '"
                                                                + column.toString()
                                                                + "' doesn't support 'is null' expression, and only '=', 'in' and 'between' are supported. source sql is ["
                                                                + sql + "]");
        } else {
            super.visit(isNullExpression);
        }
    }

    @Override
    public void visit(GreaterThan greaterThan) {
        Column column = (Column) greaterThan.getLeftExpression();
        if (getTableFromContext(column) != null) {
            throw new UnsupportedSQLExpressionException(
                                                        "Shard key '"
                                                                + column.toString()
                                                                + "' doesn't support '>' expression, and only '=', 'in' and 'between' are supported. source sql is ["
                                                                + sql + "]");
        } else {
            super.visit(greaterThan);
        }
    }

    @Override
    public void visit(GreaterThanEquals greaterThanEquals) {
        Column column = (Column) greaterThanEquals.getLeftExpression();
        if (getTableFromContext(column) != null) {
            throw new UnsupportedSQLExpressionException(
                                                        "Shard key '"
                                                                + column.toString()
                                                                + "' doesn't support '>=' expression, and only '=', 'in' and 'between' are supported. source sql is ["
                                                                + sql + "]");
        } else {
            super.visit(greaterThanEquals);
        }
    }

    @Override
    public void visit(MinorThan minorThan) {
        Column column = (Column) minorThan.getLeftExpression();
        if (getTableFromContext(column) != null) {
            throw new UnsupportedSQLExpressionException(
                                                        "Shard key '"
                                                                + column.toString()
                                                                + "' doesn't support '<' expression, and only '=', 'in' and 'between' are supported. source sql is ["
                                                                + sql + "]");
        } else {
            super.visit(minorThan);
        }
    }

    @Override
    public void visit(MinorThanEquals minorThanEquals) {
        Column column = (Column) minorThanEquals.getLeftExpression();
        if (getTableFromContext(column) != null) {
            throw new UnsupportedSQLExpressionException(
                                                        "Shard key '"
                                                                + column.toString()
                                                                + "' doesn't support '<=' expression, and only '=', 'in' and 'between' are supported. source sql is ["
                                                                + sql + "]");
        } else {
            super.visit(minorThanEquals);
        }
    }

    @Override
    public void visit(NotEqualsTo notEqualsTo) {
        Column column = (Column) notEqualsTo.getLeftExpression();
        if (getTableFromContext(column) != null) {
            throw new UnsupportedSQLExpressionException(
                                                        "Shard key '"
                                                                + column.toString()
                                                                + "' doesn't support '<>' expression, and only '=', 'in' and 'between' are supported. source sql is ["
                                                                + sql + "]");
        } else {
            super.visit(notEqualsTo);
        }
    }

    @Override
    public void visit(LikeExpression likeExpression) {
        Column column = (Column) likeExpression.getLeftExpression();
        if (getTableFromContext(column) != null) {
            throw new UnsupportedSQLExpressionException(
                                                        "Shard key '"
                                                                + column.toString()
                                                                + "' doesn't support 'like' expression, and only '=', 'in' and 'between' are supported. source sql is ["
                                                                + sql + "]");
        } else {
            super.visit(likeExpression);
        }
    }

    private TableWrapper getTableFromContext(Column col) {
        ConverterContext converterContext = this.getContext();
        StackContext stackContext = converterContext.getStack().peek();
        String colFullName = col.toString();
        colFullName = DDRStringUtils.toLowerCase(colFullName);
        return stackContext.get(colFullName);
    }

    private void addRoutedTableIntoContext(Table table, RouteConfig routeInfo) {
        addRoutedTableIntoContext(table, routeInfo, true);
    }

    /**
     * key:'scName.tbAliasName' value:table
     * @param table
     * @param appendAlias
     */
    private void addRoutedTableIntoContext(Table table, RouteConfig routeInfo, boolean appendAlias) {
        ConverterContext converterContext = this.getContext();
        StackContext stackContext = converterContext.getStack().peek();
        String tbName = table.getName();
        String tbAliasName = tbName;
        if (table.getAlias() != null && table.getAlias().getName() != null) {
            tbAliasName = table.getAlias().getName();
        } else {
            if (appendAlias) {
                table.setAlias(new Alias(tbName, true));
            }
        }
        String sdKey = DDRStringUtils.toLowerCase(routeInfo.getSdKey());
        if (table.getSchemaName() != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(DDRStringUtils.toLowerCase(table.getSchemaName()));
            sb.append('.');
            sb.append(DDRStringUtils.toLowerCase(tbAliasName));
            sb.append('.');
            sb.append(sdKey);
            String key = sb.toString();
            TableWrapper tableWrapper = new TableWrapper(table, routeInfo);
            putIntoContext(stackContext, key, tableWrapper);
            putIntoContext(stackContext, key.substring(table.getSchemaName().length() + 1), tableWrapper);
            putIntoContext(stackContext, key.substring(table.getSchemaName().length() + 2 + tbAliasName.length()),
                           tableWrapper);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(DDRStringUtils.toLowerCase(tbAliasName));
            sb.append('.');
            sb.append(sdKey);
            String key = sb.toString();
            TableWrapper tableWrapper = new TableWrapper(table, routeInfo);
            putIntoContext(stackContext, key, tableWrapper);
            putIntoContext(stackContext, key.substring(tbAliasName.length() + 1), tableWrapper);
        }
    }

    private Object getRouteValue(Column column, Expression obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof LongValue) {
            return ((LongValue) obj).getValue();
        } else if (obj instanceof StringValue) {
            return ((StringValue) obj).getValue();
        } else if (obj instanceof HexValue) {
            return Long.parseLong(((HexValue) obj).getValue(), 16);
        } else if (obj instanceof JdbcParameter) {
            JdbcParameter jdbcParameterObj = (JdbcParameter) obj;
            if (jdbcParam == null) {
                return null;
            } else {
                Object val = jdbcParam.get(jdbcParameterObj.getIndex());
                if (val == null) {
                    return null;
                } else {
                    return val;
                }
            }
        } else if (obj instanceof JdbcNamedParameter) {
            JdbcNamedParameter jdbcNamedParameter = (JdbcNamedParameter) obj;
            if (jdbcParam == null) {
                return null;
            } else {
                Object val = jdbcParam.get(jdbcNamedParameter.getName());
                if (val == null) {
                    return null;
                } else {
                    return val;
                }
            }
        } else if (obj instanceof DateValue) {// 不常见的
            return ((DateValue) obj).getValue();
        } else if (obj instanceof DoubleValue) {
            return ((DoubleValue) obj).getValue();
        } else if (obj instanceof TimeValue) {
            return ((TimeValue) obj).getValue();
        } else if (obj instanceof TimestampValue) {
            return ((TimestampValue) obj).getValue();
        } else {
            throw new UnsupportedColumnTypeException("Type '" + obj.getClass() + "' is not supported for shard key '"
                                                     + column.toString() + "', source sql is [" + sql + "]");
        }
    }

    @Override
    public void visit(InExpression inExpression) {
        Column column = (Column) inExpression.getLeftExpression();
        TableWrapper tableWrapper = getTableFromContext(column);
        if (tableWrapper == null) {
            super.visit(inExpression);
            return;
        }
        if (inExpression.isNot()) {
            throw new UnsupportedSQLExpressionException("Shard key expression '" + inExpression.toString()
                                                        + "'is not supported for it contains 'not', source sql is ["
                                                        + sql + "]");
        }
        // 普通in模式
        ExpressionList itemsList = (ExpressionList) inExpression.getRightItemsList();
        List<Expression> list = itemsList.getExpressions();
        if (list == null || list.isEmpty()) {
            throw new UnsupportedSQLExpressionException(
                                                        "Shard key expression '"
                                                                + inExpression.toString()
                                                                + "' is not supported for 'in' expression is empty. source sql is ["
                                                                + sql + "]");
        }
        for (Expression exp : list) {
            routeTable(tableWrapper, column, exp);
        }
    }

    @Override
    public void visit(Between between) {
        Column column = (Column) between.getLeftExpression();
        TableWrapper tableWrapper = getTableFromContext(column);
        if (tableWrapper == null) {
            super.visit(between);
            return;
        }
        if (between.isNot()) {
            throw new UnsupportedSQLExpressionException("Shard key expression '" + between.toString()
                                                        + "' is not supported for it contains 'not'. source sql is ["
                                                        + sql + "]");
        }
        Expression begin = between.getBetweenExpressionStart();
        Expression end = between.getBetweenExpressionEnd();
        long s = ((Number) getRouteValue(column, begin)).longValue();
        long e = ((Number) getRouteValue(column, end)).longValue();
        for (long s0 = s; s0 <= e; s0++) {
            routeTable(tableWrapper, column, s0);
        }
    }

    @Override
    public void visit(EqualsTo equalsTo) {
        Column column = (Column) equalsTo.getLeftExpression();
        String fullColumnName = column.toString();
        fullColumnName = DDRStringUtils.toLowerCase(fullColumnName);
        TableWrapper tableWrapper = this.getContext().getStack().peek().get(fullColumnName);
        if (tableWrapper != null) {// 需要路由的table
            routeTable(tableWrapper, column, equalsTo.getRightExpression());
        } else {// there maybe contains sub query,so we show invoke super.visit
            super.visit(equalsTo);
        }
    }

    private void routeTable(TableWrapper tableWrapper, Column column, Expression routeValueExpression) {
        Object val = getRouteValue(column, routeValueExpression);//
        routeTable(tableWrapper, column, val);
    }

    private void routeTable(TableWrapper tableWrapper, Column column, Object val) {
        if (tableWrapper == null) {//
            return;
        }
        if (tableWrapper == AMBIGUOUS_TABLE) {
            throw new RuntimeException("Shard key '" + column.toString()
                                       + "' in where clause is ambiguous. source sql is [" + sql + "]");
        }
        tableWrapper.setSdKey(DDRStringUtils.toLowerCase(column.getColumnName()));
        if (ShardRouteContext.isDisableSqlRouting() == Boolean.TRUE) {
            if (logger.isDebugEnabled()) {
                logger.debug("[DisableSqlRouting] scName:" + tableWrapper.getScName() + ", tbName:"
                             + tableWrapper.getTbName() + ", sdKey:" + tableWrapper.getSdKey() + ", sdValue:" + val);
            }
            return;
        }
        RouteInfo routeInfo = this.shardRouter.route(this.getContext().getTableRouterContext(),
                                                     tableWrapper.getScName(), tableWrapper.getTbName(), val);
        route0(tableWrapper, routeInfo);
    }

    private boolean equalsIgnoreCase(String str0, String str1) {
        if (str0 == null && str1 == null) {
            return true;
        } else if (str0 != null) {
            return str0.equals(str1);
        } else {
            return str1.equals(str0);
        }
    }

    @Override
    public void visit(Table table) {
        ConverterContext converterContext = this.getContext();
        String tbName = table.getName();
        RouteConfig routeInfo = shardRouter.getRouteConfig(converterContext.getTableRouterContext(),
                                                           table.getSchemaName(), tbName);
        if (routeInfo != null) {
            addRoutedTableIntoContext(table, routeInfo);
        }
    }

    private void putIntoContext(StackContext stackContext, String key, TableWrapper tableWrapper) {
        TableWrapper tableWrapper0 = stackContext.get(key);
        if (tableWrapper0 == null) {
            stackContext.put(key, tableWrapper);
        } else {
            stackContext.put(key, AMBIGUOUS_TABLE);
        }
    }

    private static final TableWrapper            AMBIGUOUS_TABLE = new TableWrapper(null, true);

    private static ThreadLocal<ConverterContext> context         = new ThreadLocal<ConverterContext>();

    private class StackContext extends HashMap<String, TableWrapper> {

    }

    private static class TableWrapper {

        private boolean     converted;
        private String      scName;   // original name
        private String      tbName;   // original name
        private String      sdKey;    // original name
        private Table       table;    //
        private RouteConfig routeInfo;

        public TableWrapper(Table table, RouteConfig routeInfo) {
            this.converted = false;
            this.table = table;
            if (table != null) {
                this.scName = DDRStringUtils.toLowerCase(table.getSchemaName());
                this.tbName = DDRStringUtils.toLowerCase(table.getName());
            }
            this.routeInfo = routeInfo;
        }

        private TableWrapper(Table table, boolean converted) {
            this.converted = converted;
            this.table = table;
            if (table != null) {
                this.scName = table.getSchemaName();
                this.tbName = table.getName();
            }
        }

        public String getScName() {
            return scName;
        }

        public void setScName(String scName) {
            this.scName = scName;
        }

        public String getTbName() {
            return tbName;
        }

        public void setTbName(String tbName) {
            this.tbName = tbName;
        }

        public String getSdKey() {
            return sdKey;
        }

        public void setSdKey(String sdKey) {
            this.sdKey = sdKey;
        }

        public Table getTable() {
            return table;
        }

        public void setTable(Table table) {
            this.table = table;
        }

        public boolean isConverted() {
            return converted;
        }

        public void setConverted(boolean converted) {
            this.converted = converted;
        }

        public RouteConfig getRouteInfo() {
            return routeInfo;
        }

        public void setRouteInfo(RouteConfig routeInfo) {
            this.routeInfo = routeInfo;
        }

        @Override
        public String toString() {
            return new DDRToStringBuilder()//
            .append("scName", scName)//
            .append("tbName", tbName)//
            .append("sdKey", sdKey)//
            .append("routeInfo", routeInfo)//
            .toString();
        }
    }

    private class ConverterContext {

        private Stack<StackContext>    stack              = null;
        private ShardRouteParamContext tableRouterContext = null;

        private class TableRouterContextImpl extends HashMap<String, Object> implements ShardRouteParamContext {

        }

        public ConverterContext() {
            this.stack = new Stack<StackContext>();
            this.tableRouterContext = new TableRouterContextImpl();
        }

        public ConverterContext(Stack<StackContext> stack, ShardRouteParamContext tableRouterContext) {
            this.stack = stack;
            this.tableRouterContext = tableRouterContext;
        }

        public Stack<StackContext> getStack() {
            return stack;
        }

        public ShardRouteParamContext getTableRouterContext() {
            return tableRouterContext;
        }
    }

    private ConverterContext getContext() {
        return context.get();
    }

}