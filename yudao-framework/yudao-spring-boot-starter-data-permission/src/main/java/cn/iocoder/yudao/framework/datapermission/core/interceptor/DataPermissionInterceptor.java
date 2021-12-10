package cn.iocoder.yudao.framework.datapermission.core.interceptor;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.util.collection.SetUtils;
import cn.iocoder.yudao.framework.datapermission.core.rule.DataPermissionRule;
import cn.iocoder.yudao.framework.datapermission.core.rule.DataPermissionRuleFactory;
import com.alibaba.ttl.TransmittableThreadLocal;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.core.toolkit.StringPool;
import com.baomidou.mybatisplus.extension.parser.JsqlParserSupport;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class DataPermissionInterceptor extends JsqlParserSupport implements InnerInterceptor {

    private final DataPermissionRuleFactory ruleFactory;

    private final MappedStatementCache mappedStatementCache = new MappedStatementCache();

    @Override // SELECT 场景
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        // 获得 Mapper 对应的数据权限的规则
        List<DataPermissionRule> rules = ruleFactory.getDataPermissionRule(ms.getId());
        if (mappedStatementCache.noRewritable(ms, rules)) { // 如果无需重写，则跳过
            return;
        }

        PluginUtils.MPBoundSql mpBs = PluginUtils.mpBoundSql(boundSql);
        try {
            // 初始化上下文
            ContextHolder.init(rules);
            // 处理 SQL
            mpBs.sql(parserSingle(mpBs.sql(), null));
        } finally {
            addMappedStatementCache(ms);
            ContextHolder.clear();
        }
    }

    @Override // 只处理 UPDATE / DELETE 场景
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        PluginUtils.MPStatementHandler mpSh = PluginUtils.mpStatementHandler(sh);
        MappedStatement ms = mpSh.mappedStatement();
        SqlCommandType sct = ms.getSqlCommandType();
        if (sct == SqlCommandType.UPDATE || sct == SqlCommandType.DELETE) { // 无需处理 Insert 语句
            // 获得 Mapper 对应的数据权限的规则
            List<DataPermissionRule> rules = ruleFactory.getDataPermissionRule(ms.getId());
            if (mappedStatementCache.noRewritable(ms, rules)) { // 如果无需重写，则跳过
                return;
            }

            PluginUtils.MPBoundSql mpBs = mpSh.mPBoundSql();
            try {
                // 初始化上下文
                ContextHolder.init(rules);
                // 处理 SQL
                mpBs.sql(parserMulti(mpBs.sql(), null));
            } finally {
                addMappedStatementCache(ms);
                ContextHolder.clear();
            }
        }
    }

    @Override
    protected void processSelect(Select select, int index, String sql, Object obj) {
        processSelectBody(select.getSelectBody());
        List<WithItem> withItemsList = select.getWithItemsList();
        if (!CollectionUtils.isEmpty(withItemsList)) {
            withItemsList.forEach(this::processSelectBody);
        }
    }

    protected void processSelectBody(SelectBody selectBody) {
        if (selectBody == null) {
            return;
        }
        if (selectBody instanceof PlainSelect) {
            processPlainSelect((PlainSelect) selectBody);
        } else if (selectBody instanceof WithItem) {
            WithItem withItem = (WithItem) selectBody;
            processSelectBody(withItem.getSubSelect().getSelectBody());
        } else {
            SetOperationList operationList = (SetOperationList) selectBody;
            List<SelectBody> selectBodys = operationList.getSelects();
            if (CollectionUtils.isNotEmpty(selectBodys)) {
                selectBodys.forEach(this::processSelectBody);
            }
        }
    }

    /**
     * update 语句处理
     */
    @Override
    protected void processUpdate(Update update, int index, String sql, Object obj) {
        final Table table = update.getTable();
        update.setWhere(this.andExpression(table, update.getWhere()));
    }

    /**
     * delete 语句处理
     */
    @Override
    protected void processDelete(Delete delete, int index, String sql, Object obj) {
        delete.setWhere(this.andExpression(delete.getTable(), delete.getWhere()));
    }

    /**
     * delete update 语句 where 处理
     */
    protected BinaryExpression andExpression(Table table, Expression where) {
        //获得where条件表达式
        EqualsTo equalsTo = new EqualsTo();
        equalsTo.setLeftExpression(this.getAliasColumn(table));
        equalsTo.setRightExpression(getTenantId());
        if (null != where) {
            if (where instanceof OrExpression) {
                return new AndExpression(equalsTo, new Parenthesis(where));
            } else {
                return new AndExpression(equalsTo, where);
            }
        }
        return equalsTo;
    }

    /**
     * 处理 PlainSelect
     */
    protected void processPlainSelect(PlainSelect plainSelect) {
        FromItem fromItem = plainSelect.getFromItem();
        Expression where = plainSelect.getWhere();
        processWhereSubSelect(where);
        if (fromItem instanceof Table) {
            Table fromTable = (Table) fromItem;
            if (!ignoreTable(fromTable.getName())) {
                //#1186 github
                plainSelect.setWhere(builderExpression(where, fromTable));
            }
        } else {
            processFromItem(fromItem);
        }
        //#3087 github
        List<SelectItem> selectItems = plainSelect.getSelectItems();
        if (CollectionUtils.isNotEmpty(selectItems)) {
            selectItems.forEach(this::processSelectItem);
        }
        List<Join> joins = plainSelect.getJoins();
        if (CollectionUtils.isNotEmpty(joins)) {
            processJoins(joins);
        }
    }

    /**
     * 处理where条件内的子查询
     * <p>
     * 支持如下:
     * 1. in
     * 2. =
     * 3. >
     * 4. <
     * 5. >=
     * 6. <=
     * 7. <>
     * 8. EXISTS
     * 9. NOT EXISTS
     * <p>
     * 前提条件:
     * 1. 子查询必须放在小括号中
     * 2. 子查询一般放在比较操作符的右边
     *
     * @param where where 条件
     */
    protected void processWhereSubSelect(Expression where) {
        if (where == null) {
            return;
        }
        if (where instanceof FromItem) {
            processFromItem((FromItem) where);
            return;
        }
        if (where.toString().indexOf("SELECT") > 0) {
            // 有子查询
            if (where instanceof BinaryExpression) {
                // 比较符号 , and , or , 等等
                BinaryExpression expression = (BinaryExpression) where;
                processWhereSubSelect(expression.getLeftExpression());
                processWhereSubSelect(expression.getRightExpression());
            } else if (where instanceof InExpression) {
                // in
                InExpression expression = (InExpression) where;
                ItemsList itemsList = expression.getRightItemsList();
                if (itemsList instanceof SubSelect) {
                    processSelectBody(((SubSelect) itemsList).getSelectBody());
                }
            } else if (where instanceof ExistsExpression) {
                // exists
                ExistsExpression expression = (ExistsExpression) where;
                processWhereSubSelect(expression.getRightExpression());
            } else if (where instanceof NotExpression) {
                // not exists
                NotExpression expression = (NotExpression) where;
                processWhereSubSelect(expression.getExpression());
            } else if (where instanceof Parenthesis) {
                Parenthesis expression = (Parenthesis) where;
                processWhereSubSelect(expression.getExpression());
            }
        }
    }

    protected void processSelectItem(SelectItem selectItem) {
        if (selectItem instanceof SelectExpressionItem) {
            SelectExpressionItem selectExpressionItem = (SelectExpressionItem) selectItem;
            if (selectExpressionItem.getExpression() instanceof SubSelect) {
                processSelectBody(((SubSelect) selectExpressionItem.getExpression()).getSelectBody());
            } else if (selectExpressionItem.getExpression() instanceof Function) {
                processFunction((Function) selectExpressionItem.getExpression());
            }
        }
    }

    /**
     * 处理函数
     * <p>支持: 1. select fun(args..) 2. select fun1(fun2(args..),args..)<p>
     * <p> fixed gitee pulls/141</p>
     *
     * @param function
     */
    protected void processFunction(Function function) {
        ExpressionList parameters = function.getParameters();
        if (parameters != null) {
            parameters.getExpressions().forEach(expression -> {
                if (expression instanceof SubSelect) {
                    processSelectBody(((SubSelect) expression).getSelectBody());
                } else if (expression instanceof Function) {
                    processFunction((Function) expression);
                }
            });
        }
    }

    /**
     * 处理子查询等
     */
    protected void processFromItem(FromItem fromItem) {
        if (fromItem instanceof SubJoin) {
            SubJoin subJoin = (SubJoin) fromItem;
            if (subJoin.getJoinList() != null) {
                processJoins(subJoin.getJoinList());
            }
            if (subJoin.getLeft() != null) {
                processFromItem(subJoin.getLeft());
            }
        } else if (fromItem instanceof SubSelect) {
            SubSelect subSelect = (SubSelect) fromItem;
            if (subSelect.getSelectBody() != null) {
                processSelectBody(subSelect.getSelectBody());
            }
        } else if (fromItem instanceof ValuesList) {
            logger.debug("Perform a subquery, if you do not give us feedback");
        } else if (fromItem instanceof LateralSubSelect) {
            LateralSubSelect lateralSubSelect = (LateralSubSelect) fromItem;
            if (lateralSubSelect.getSubSelect() != null) {
                SubSelect subSelect = lateralSubSelect.getSubSelect();
                if (subSelect.getSelectBody() != null) {
                    processSelectBody(subSelect.getSelectBody());
                }
            }
        }
    }

    /**
     * 处理 joins
     *
     * @param joins join 集合
     */
    private void processJoins(List<Join> joins) {
        //对于 on 表达式写在最后的 join，需要记录下前面多个 on 的表名
        Deque<Table> tables = new LinkedList<>();
        for (Join join : joins) {
            // 处理 on 表达式
            FromItem fromItem = join.getRightItem();
            if (fromItem instanceof Table) {
                Table fromTable = (Table) fromItem;
                // 获取 join 尾缀的 on 表达式列表
                Collection<Expression> originOnExpressions = join.getOnExpressions();
                // 正常 join on 表达式只有一个，立刻处理
                if (originOnExpressions.size() == 1) {
                    processJoin(join);
                    continue;
                }
                // 当前表是否忽略
                boolean needIgnore = ignoreTable(fromTable.getName());
                // 表名压栈，忽略的表压入 null，以便后续不处理
                tables.push(needIgnore ? null : fromTable);
                // 尾缀多个 on 表达式的时候统一处理
                if (originOnExpressions.size() > 1) {
                    Collection<Expression> onExpressions = new LinkedList<>();
                    for (Expression originOnExpression : originOnExpressions) {
                        Table currentTable = tables.poll();
                        if (currentTable == null) {
                            onExpressions.add(originOnExpression);
                        } else {
                            onExpressions.add(builderExpression(originOnExpression, currentTable));
                        }
                    }
                    join.setOnExpressions(onExpressions);
                }
            } else {
                // 处理右边连接的子表达式
                processFromItem(fromItem);
            }
        }
    }

    /**
     * 处理联接语句
     */
    protected void processJoin(Join join) {
        if (join.getRightItem() instanceof Table) {
            Table fromTable = (Table) join.getRightItem();
            if (ignoreTable(fromTable.getName())) {
                // 过滤退出执行
                return;
            }
            // 走到这里说明 on 表达式肯定只有一个
            Collection<Expression> originOnExpressions = join.getOnExpressions();
            List<Expression> onExpressions = new LinkedList<>();
            onExpressions.add(builderExpression(originOnExpressions.iterator().next(), fromTable));
            join.setOnExpressions(onExpressions);
        }
    }

    /**
     * 处理条件
     */
    protected Expression builderExpression(Expression currentExpression, Table table) {
        EqualsTo equalsTo = new EqualsTo();
        equalsTo.setLeftExpression(this.getAliasColumn(table));
        equalsTo.setRightExpression(getTenantId());
        if (currentExpression == null) {
            return equalsTo;
        }
        if (currentExpression instanceof OrExpression) {
            return new AndExpression(new Parenthesis(currentExpression), equalsTo);
        } else {
            return new AndExpression(currentExpression, equalsTo);
        }
    }

    /**
     * 租户字段别名设置
     * <p>tenantId 或 tableAlias.tenantId</p>
     *
     * @param table 表对象
     * @return 字段
     */
    protected Column getAliasColumn(Table table) {
        StringBuilder column = new StringBuilder();
        if (table.getAlias() != null) {
            column.append(table.getAlias().getName()).append(StringPool.DOT);
        }
        column.append(getTenantIdColumn());
        return new Column(column.toString());
    }

    // TODO 芋艿：未实现

    private boolean ignoreTable(String tableName) {
        return false;
    }

    private String getTenantIdColumn() {
        return "dept_id";
    }

    private Expression getTenantId() {
        return new LongValue(1L);
    }


    /**
     * 判断 SQL 是否重写。如果没有重写，则添加到 {@link MappedStatementCache} 中
     *
     * @param ms MappedStatement
     */
    private void addMappedStatementCache(MappedStatement ms) {
        if (ContextHolder.getRewrite()) {
            return;
        }
        // 有重写，进行添加
        mappedStatementCache.addNoRewritable(ms, ContextHolder.getRules());
    }

    /**
     * SQL 解析上下文，方便透传 {@link DataPermissionRule} 规则
     *
     * @author 芋道源码
     */
    private static final class ContextHolder {

        /**
         * 该 {@link MappedStatement} 对应的规则
         */
        private static final ThreadLocal<List<DataPermissionRule>> RULES = new TransmittableThreadLocal<>();
        /**
         * SQL 是否进行重写
         */
        private static final ThreadLocal<Boolean> REWRITE = new TransmittableThreadLocal<>();

        public static void init(List<DataPermissionRule> rules) {
            RULES.set(rules);
            REWRITE.set(false);
        }

        public static void clear() {
            RULES.remove();
            REWRITE.remove();
        }

        public static boolean getRewrite() {
            return REWRITE.get();
        }

        public static void setRewrite(boolean rewrite) {
            REWRITE.set(rewrite);
        }

        public static List<DataPermissionRule> getRules() {
            return RULES.get();
        }

    }

    /**
     * {@link MappedStatement} 缓存
     * 目前主要用于，记录 {@link DataPermissionRule} 是否对指定 {@link MappedStatement} 无效
     * 如果无效，则可以避免 SQL 的解析，加快速度
     *
     * @author 芋道源码
     */
    private static final class MappedStatementCache {

        /**
         * 无需重写的映射
         *
         * value：{@link MappedStatement#getId()} 编号
         */
        private final Map<Class<? extends DataPermissionRule>, Set<String>> noRewritableMappedStatements = new ConcurrentHashMap<>();

        /**
         * 判断是否无需重写
         * ps：虽然有点中文式英语，但是容易读懂即可
         *
         * @param ms MappedStatement
         * @param rules 数据权限规则数组
         * @return 是否无需重写
         */
        public boolean noRewritable(MappedStatement ms, List<DataPermissionRule> rules) {
            // 如果规则为空，说明无需重写
            if (CollUtil.isEmpty(rules)) {
                return true;
            }
            // 任一规则不在 noRewritableMap 中，则说明可能需要重写
            for (DataPermissionRule rule : rules) {
                Set<String> mappedStatementIds = noRewritableMappedStatements.get(rule.getClass());
                if (!CollUtil.contains(mappedStatementIds, ms.getId())) { // 不存在，则说明可能要重写
                    return false;
                }
            }
            return true;
        }

        /**
         * 添加无需重写的 MappedStatement
         *
         * @param ms MappedStatement
         * @param rules 数据权限规则数组
         */
        public void addNoRewritable(MappedStatement ms, List<DataPermissionRule> rules) {
            for (DataPermissionRule rule : rules) {
                Set<String> mappedStatementIds = noRewritableMappedStatements.get(rule.getClass());
                if (CollUtil.isNotEmpty(mappedStatementIds)) {
                    mappedStatementIds.add(ms.getId());
                } else {
                    noRewritableMappedStatements.put(rule.getClass(), SetUtils.asSet(ms.getId()));
                }
            }
        }

    }

}
