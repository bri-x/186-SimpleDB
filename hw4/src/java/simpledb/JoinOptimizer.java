package simpledb;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.util.*;

/**
 * The JoinOptimizer class is responsible for ordering a series of joins
 * optimally, and for selecting the best instantiation of a join for a given
 * logical plan.
 */
public class JoinOptimizer {
    LogicalPlan p;
    Vector<LogicalJoinNode> joins;

    /**
     * Constructor
     * 
     * @param p
     *            the logical plan being optimized
     * @param joins
     *            the list of joins being performed
     */
    public JoinOptimizer(LogicalPlan p, Vector<LogicalJoinNode> joins) {
        this.p = p;
        this.joins = joins;
    }

    /**
     * Return best iterator for computing a given logical join, given the
     * specified statistics, and the provided left and right subplans. Note that
     * there is insufficient information to determine which plan should be the
     * inner/outer here -- because DbIterator's don't provide any cardinality
     * estimates, and stats only has information about the base tables. For this
     * reason, the plan1
     * 
     * @param lj
     *            The join being considered
     * @param plan1
     *            The left join node's child
     * @param plan2
     *            The right join node's child
     */
    public static DbIterator instantiateJoin(LogicalJoinNode lj,
            DbIterator plan1, DbIterator plan2) throws ParsingException {

        int t1id = 0, t2id = 0;
        DbIterator j;

        try {
            t1id = plan1.getTupleDesc().fieldNameToIndex(lj.f1QuantifiedName);
        } catch (NoSuchElementException e) {
            throw new ParsingException("Unknown field " + lj.f1QuantifiedName);
        }

        if (lj instanceof LogicalSubplanJoinNode) {
            t2id = 0;
        } else {
            try {
                t2id = plan2.getTupleDesc().fieldNameToIndex(
                        lj.f2QuantifiedName);
            } catch (NoSuchElementException e) {
                throw new ParsingException("Unknown field "
                        + lj.f2QuantifiedName);
            }
        }

        JoinPredicate p = new JoinPredicate(t1id, lj.p, t2id);

        j = new Join(p,plan1,plan2);

        return j;

    }

    /**
     * Estimate the cost of a join.
     * 
     * The cost of the join should be calculated based on the join algorithm (or
     * algorithms) that you implemented for Lab 2. It should be a function of
     * the amount of data that must be read over the course of the query, as
     * well as the number of CPU opertions performed by your join. Assume that
     * the cost of a single predicate application is roughly 1.
     * 
     * 
     * @param j
     *            A LogicalJoinNode representing the join operation being
     *            performed.
     * @param card1
     *            Estimated cardinality of the left-hand side of the query
     * @param card2
     *            Estimated cardinality of the right-hand side of the query
     * @param cost1
     *            Estimated cost of one full scan of the table on the left-hand
     *            side of the query
     * @param cost2
     *            Estimated cost of one full scan of the table on the right-hand
     *            side of the query
     * @return An estimate of the cost of this query, in terms of cost1 and
     *         cost2
     */
    public double estimateJoinCost(LogicalJoinNode j, int card1, int card2,
            double cost1, double cost2) {
        if (j instanceof LogicalSubplanJoinNode) {
            // A LogicalSubplanJoinNode represents a subquery.
            // Don't worry about implementing a more sophisticated estimate than the one below.
            return card1 + cost1 + cost2;
        } else {

            return cost1 + card1 * cost2 + card1 * card2;
        }
    }

    /**
     * Estimate the cardinality of a join. The cardinality of a join is the
     * number of tuples produced by the join.
     * 
     * @param j
     *            A LogicalJoinNode representing the join operation being
     *            performed.
     * @param card1
     *            Cardinality of the left-hand table in the join
     * @param card2
     *            Cardinality of the right-hand table in the join
     * @param t1pkey
     *            Is the left-hand table a primary-key table?
     * @param t2pkey
     *            Is the right-hand table a primary-key table?
     * @param stats
     *            The table stats, referenced by table names, not alias
     * @return The cardinality of the join
     */
    public int estimateJoinCardinality(LogicalJoinNode j, int card1, int card2,
            boolean t1pkey, boolean t2pkey, Map<String, TableStats> stats) {
        if (j instanceof LogicalSubplanJoinNode) {
            // A LogicalSubplanJoinNode represents a subquery.
            // Don't worry about implementing a more sophisticated estimate than the one below.
            return card1;
        } else {
            return estimateTableJoinCardinality(j.p, j.t1Alias, j.t2Alias,
                    j.f1PureName, j.f2PureName, card1, card2, t1pkey, t2pkey,
                    stats, p.getTableAliasToIdMapping());
        }
    }

    /**
     * Estimate the join cardinality of two tables.
     * */
    public static int estimateTableJoinCardinality(Predicate.Op joinOp,
            String table1Alias, String table2Alias, String field1PureName,
            String field2PureName, int card1, int card2, boolean t1pkey,
            boolean t2pkey, Map<String, TableStats> stats,
            Map<String, Integer> tableAliasToId) {

        if (joinOp == Predicate.Op.EQUALS) {
            if (t1pkey) {
                return card2;
            } else if (t2pkey) {
                return card1;
            } else {
                return Math.max(card1, card2);
            }
        } else {
            return (int)(0.3 * card1 * card2);
        }
    }

    /**
     * Compute a logical, reasonably efficient join on the specified tables.
     * If we have more than 10 joins, we use the greedy solution.
     * Otherwise, we'll use the dynamic programming solution.
     *
     * @param stats
     *            Statistics for each table involved in the join, referenced by
     *            base table names, not alias
     * @param filterSelectivities
     *            Selectivities of the filter predicates on each table in the
     *            join, referenced by table alias (if no alias, the base table
     *            name)
     * @return A Vector<LogicalJoinNode> that stores joins in the left-deep
     *         order in which they should be executed.
     * @throws ParsingException
     *             when stats or filter selectivities is missing a table in the
     *             join, or or when another internal error occurs
     */
    public Vector<LogicalJoinNode> orderJoins(
            HashMap<String, TableStats> stats,
            HashMap<String, Double> filterSelectivities)
            throws ParsingException {

        if (joins.size() > 10) {
            return orderGreedyJoins(stats, filterSelectivities);
        } else {
            return orderDynamicProgrammingJoins(stats, filterSelectivities);
        }
    }

    /**
     * Compute a logical, reasonably efficient join on the specified tables,
     * using the Greedy Algorithm as defined in the README.
     *
     * @return A Vector<LogicalJoinNode> that stores joins in the left-deep
     *         order in which they should be executed.
     * @throws ParsingException
     */
    // public Vector<LogicalJoinNode> orderGreedyJoins(
    //         HashMap<String, TableStats> stats,
    //         HashMap<String, Double> filterSelectivities)
    //         throws ParsingException {

    //     // keeps track of the order of joins we will execute in our final plan
    //     Vector<LogicalJoinNode> plan = new Vector<>();

    //     // each LogicalJoinNode represents an intermediate join table, and this
    //     // vector represents the cardinality of that table
    //     Vector<Integer> planCardinalities = new Vector<>();

    //     // Likewise for this vector, except for costs of creating those intermediate tables
    //     Vector<Double> planCosts = new Vector<>();

    //     // TODO: IMPLEMENT ME

    //     return plan;
    // }
    public Vector<LogicalJoinNode> orderGreedyJoins(
            HashMap<String, TableStats> stats,
            HashMap<String, Double> filterSelectivities)
            throws ParsingException {

        // keeps track of the order of joins we will execute in our final plan
        Vector<LogicalJoinNode> plan = new Vector<>();

        // each LogicalJoinNode represents an intermediate join table, and this
        // vector represents the cardinality of that table
        Vector<Integer> planCardinalities = new Vector<>();

        // Likewise for this vector, except for costs of creating those intermediate tables
        Vector<Double> planCosts = new Vector<>();

        //all the joins
        Vector<LogicalJoinNode> joinsLeft = new Vector<>(joins);

        while (!joinsLeft.isEmpty()) {

            CostCard cheapest = new CostCard();
            cheapest.plan = null;
            cheapest.cost = Double.MAX_VALUE;
            cheapest.card = Integer.MAX_VALUE;
            LogicalJoinNode best = null;

            for (LogicalJoinNode joinNode : joinsLeft) {
                CostCard planCostCard = costGreedyJoin(joinNode, plan, planCardinalities, planCosts,
                        stats, filterSelectivities);
                if (planCostCard != null) {
                    if (planCostCard.cost < cheapest.cost) {
                        cheapest = planCostCard;
                        best = joinNode;
                    }
                }
            }
            if (best != null) {
                plan.add(best);
                joinsLeft.remove(best);
                planCardinalities.add(cheapest.card);
                planCosts.add(cheapest.cost);
            }
        }

        return plan;
    }


    /**
     * Compute a logical, reasonably efficient join on the specified tables,
     * using the Dynamic Programming algorithm as defined in the README and
     * in lecture.
     *
     * @return A Vector<LogicalJoinNode> that stores joins in the left-deep
     *         order in which they should be executed.
     * @throws ParsingException
     */
    public Vector<LogicalJoinNode> orderDynamicProgrammingJoins(
            HashMap<String, TableStats> stats,
            HashMap<String, Double> filterSelectivities)
            throws ParsingException {

        PlanCache cache = new PlanCache();
        Set<LogicalJoinNode> allJoins = new HashSet<>(joins);

        for (int i = 1; i <= joins.size(); i += 1){
            for(Set<LogicalJoinNode> joinSet : enumerateSubsets(joins, i)) {

                CostCard best = new CostCard();
                best.plan = null;
                best.cost = Double.MAX_VALUE;
                best.card = Integer.MAX_VALUE;
                for (LogicalJoinNode joinNode : joinSet) {
                    CostCard planCostCard = computeCostAndCardOfSubplan(stats, filterSelectivities,
                            joinNode, joinSet, best.cost, cache);
                    if (planCostCard != null) {
                        if (planCostCard.cost < best.cost) {
                            best = planCostCard;
                        }
                        cache.addPlan(joinSet, best.cost, best.card, best.plan);
                    }
                }

            }
        }

        return cache.getOrder(allJoins);
    }

    // ===================== Private Methods =================================

    /**
     * Helper method to enumerate all of the subsets of a given size of a
     * specified vector.
     *
     * @param v
     *            The vector whose subsets are desired
     * @param size
     *            The size of the subsets of interest
     * @return a set of all subsets of the specified size
     */
    @SuppressWarnings("unchecked")
    private <T> Set<Set<T>> enumerateSubsets(Vector<T> v, int size) {
        Set<Set<T>> els = new HashSet<Set<T>>();
        els.add(new HashSet<T>());

        for (int i = 0; i < size; i++) {
            Set<Set<T>> newels = new HashSet<Set<T>>();
            for (Set<T> s : els) {
                for (T t : v) {
                    Set<T> news = (Set<T>) (((HashSet<T>) s).clone());
                    if (news.add(t))
                        newels.add(news);
                }
            }
            els = newels;
        }

        return els;
    }

    /**
     * This is a helper method that computes the cost and cardinality of joining
     * a LogicalJoinNode j to the current greedy plan we have built up.
     *
     * @param j
     *            the join to try adding to our plan
     * @param plan
     *            the current plan we have built so far from the greedy algorithm,
     *            a Vector of LogicalJoinNodes that we've so far chosen.
     * @param planCardinalities
     *            given the join order from plan, we also keep track of how large
     *            joined tables are, so we can help estimate the cardinality and cost
     *            of this next join
     * @param planCosts
     *            given the join order from plan, we also keep track of how expensive
     *            executing some joins are, so we can help estimate the cardinality
     *            and cost of this next join
     * @param stats
     *            table stats for all of the tables, referenced by table names
     *            rather than alias (see {@link #orderGreedyJoins(HashMap, HashMap)})
     * @param filterSelectivities
     *            the selectivities of the filters over each of the tables
     *            (where tables are indentified by their alias or name if no
     *            alias is given)
     * @return A {@link CostCard} objects desribing the cost, cardinality,
     *         optimal subplan
     * @throws ParsingException
     *             when stats, filterSelectivities, or pc object is missing
     *             tables involved in join
     */
    private CostCard costGreedyJoin(LogicalJoinNode j,
                                    Vector<LogicalJoinNode> plan,
                                    Vector<Integer> planCardinalities,
                                    Vector<Double> planCosts,
                                    HashMap<String, TableStats> stats,
                                    HashMap<String, Double> filterSelectivities) throws ParsingException {

        if (this.p.getTableId(j.t1Alias) == null)
            throw new ParsingException("Unknown table " + j.t1Alias);
        if (this.p.getTableId(j.t2Alias) == null)
            throw new ParsingException("Unknown table " + j.t2Alias);

        String table1Name = Database.getCatalog().getTableName(
                this.p.getTableId(j.t1Alias));
        String table2Name = Database.getCatalog().getTableName(
                this.p.getTableId(j.t2Alias));
        String table1Alias = j.t1Alias;
        String table2Alias = j.t2Alias;

        double t1cost, t2cost;
        int t1card, t2card;
        boolean leftPkey, rightPkey;

        // estimate cost of right subtree
        if (doesJoin(plan, table1Alias)) { // j.t1 is in plan already
            CostCard c = getCostCard(plan, planCardinalities, planCosts, table1Alias);
            t1cost = c.cost; // left side just has cost of whatever left subtree is
            t1card = c.card;
            leftPkey = hasPkey(plan);

            t2cost = j.t2Alias == null ? 0 : stats.get(table2Name)
                    .estimateScanCost();
            t2card = j.t2Alias == null ? 0 : stats.get(table2Name)
                    .estimateTableCardinality(
                            filterSelectivities.get(j.t2Alias));
            rightPkey = j.t2Alias == null ? false : isPkey(j.t2Alias,
                    j.f2PureName);
        } else if (doesJoin(plan, j.t2Alias)) { // j.t2 is in plan
            // (else if since both j.t1 and j.t2 shouldn't both be)
            CostCard c = getCostCard(plan, planCardinalities, planCosts, table2Alias);
            t2cost = c.cost;
            t2card = c.card;
            rightPkey = hasPkey(plan);

            t1cost = stats.get(table1Name).estimateScanCost();
            t1card = stats.get(table1Name).estimateTableCardinality(
                    filterSelectivities.get(j.t1Alias));
            leftPkey = isPkey(j.t1Alias, j.f1PureName);

        } else { // Neither is a plan, both are just single tables
            t1cost = stats.get(table1Name).estimateScanCost();
            t1card = stats.get(table1Name).estimateTableCardinality(
                    filterSelectivities.get(j.t1Alias));
            leftPkey = isPkey(j.t1Alias, j.f1PureName);

            t2cost = table2Alias == null ? 0 : stats.get(table2Name)
                    .estimateScanCost();
            t2card = table2Alias == null ? 0 : stats.get(table2Name)
                    .estimateTableCardinality(
                            filterSelectivities.get(j.t2Alias));
            rightPkey = table2Alias == null ? false : isPkey(table2Alias,
                    j.f2PureName);
        }

        double cost1 = estimateJoinCost(j, t1card, t2card, t1cost, t2cost);

        LogicalJoinNode j2 = j.swapInnerOuter();
        double cost2 = estimateJoinCost(j2, t2card, t1card, t2cost, t1cost);
        if (cost2 < cost1) {
            boolean tmp;
            j = j2;
            cost1 = cost2;
            tmp = rightPkey;
            rightPkey = leftPkey;
            leftPkey = tmp;
        }

        CostCard cc = new CostCard();
        cc.card = estimateJoinCardinality(j, t1card, t2card, leftPkey,
                rightPkey, stats);
        cc.cost = cost1;
        return cc;
    }

    /**
     * Find the table in the plan, and return the cost and cardinality of
     * the intermediate joined table during the execution
     */
    private CostCard getCostCard(Vector<LogicalJoinNode> plan,
                                 Vector<Integer> planCardinalities,
                                 Vector<Double> planCosts,
                                 String table) {

        for (int i = plan.size() - 1; i >= 0; i--) {
            LogicalJoinNode j = plan.get(i);
            if (j.t1Alias.equals(table) || (j.t2Alias != null && j.t2Alias.equals(table))) {
                CostCard c = new CostCard();
                c.card = planCardinalities.get(i);
                c.cost = planCosts.get(i);
                return c;
            }
        }
        return null;
    }

    /**
     * This is a helper method that computes the cost and cardinality of joining
     * joinToRemove to joinSet (joinSet should contain joinToRemove), given that
     * all of the subsets of size joinSet.size() - 1 have already been computed
     * and stored in PlanCache pc.
     * 
     * @param stats
     *            table stats for all of the tables, referenced by table names
     *            rather than alias (see {@link #orderDynamicProgrammingJoins(HashMap, HashMap)})
     * @param filterSelectivities
     *            the selectivities of the filters over each of the tables
     *            (where tables are indentified by their alias or name if no
     *            alias is given)
     * @param joinToRemove
     *            the join to remove from joinSet
     * @param joinSet
     *            the set of joins being considered
     * @param bestCostSoFar
     *            the best way to join joinSet so far (minimum of previous
     *            invocations of computeCostAndCardOfSubplan for this joinSet,
     *            from returned CostCard)
     * @param pc
     *            the PlanCache for this join; should have subplans for all
     *            plans of size joinSet.size()-1
     * @return A {@link CostCard} objects desribing the cost, cardinality,
     *         optimal subplan
     * @throws ParsingException
     *             when stats, filterSelectivities, or pc object is missing
     *             tables involved in join
     */
    @SuppressWarnings("unchecked")
    private CostCard computeCostAndCardOfSubplan(
            HashMap<String, TableStats> stats,
            HashMap<String, Double> filterSelectivities,
            LogicalJoinNode joinToRemove, Set<LogicalJoinNode> joinSet,
            double bestCostSoFar, PlanCache pc) throws ParsingException {

        LogicalJoinNode j = joinToRemove;

        Vector<LogicalJoinNode> prevBest;

        if (this.p.getTableId(j.t1Alias) == null)
            throw new ParsingException("Unknown table " + j.t1Alias);
        if (this.p.getTableId(j.t2Alias) == null)
            throw new ParsingException("Unknown table " + j.t2Alias);

        String table1Name = Database.getCatalog().getTableName(
                this.p.getTableId(j.t1Alias));
        String table2Name = Database.getCatalog().getTableName(
                this.p.getTableId(j.t2Alias));
        String table1Alias = j.t1Alias;
        String table2Alias = j.t2Alias;

        Set<LogicalJoinNode> news = (Set<LogicalJoinNode>) ((HashSet<LogicalJoinNode>) joinSet)
                .clone();
        news.remove(j);

        double t1cost, t2cost;
        int t1card, t2card;
        boolean leftPkey, rightPkey;

        if (news.isEmpty()) { // base case -- both are base relations
            prevBest = new Vector<LogicalJoinNode>();
            t1cost = stats.get(table1Name).estimateScanCost();
            t1card = stats.get(table1Name).estimateTableCardinality(
                    filterSelectivities.get(j.t1Alias));
            leftPkey = isPkey(j.t1Alias, j.f1PureName);

            t2cost = table2Alias == null ? 0 : stats.get(table2Name)
                    .estimateScanCost();
            t2card = table2Alias == null ? 0 : stats.get(table2Name)
                    .estimateTableCardinality(
                            filterSelectivities.get(j.t2Alias));
            rightPkey = table2Alias == null ? false : isPkey(table2Alias,
                    j.f2PureName);
        } else {
            // news is not empty -- figure best way to join j to news
            prevBest = pc.getOrder(news);

            // possible that we have not cached an answer, if subset
            // includes a cross product
            if (prevBest == null) {
                return null;
            }

            double prevBestCost = pc.getCost(news);
            int bestCard = pc.getCard(news);

            // estimate cost of right subtree
            if (doesJoin(prevBest, table1Alias)) { // j.t1 is in prevBest
                t1cost = prevBestCost; // left side just has cost of whatever
                                       // left
                // subtree is
                t1card = bestCard;
                leftPkey = hasPkey(prevBest);

                t2cost = j.t2Alias == null ? 0 : stats.get(table2Name)
                        .estimateScanCost();
                t2card = j.t2Alias == null ? 0 : stats.get(table2Name)
                        .estimateTableCardinality(
                                filterSelectivities.get(j.t2Alias));
                rightPkey = j.t2Alias == null ? false : isPkey(j.t2Alias,
                        j.f2PureName);
            } else if (doesJoin(prevBest, j.t2Alias)) { // j.t2 is in prevbest
                                                        // (both
                // shouldn't be)
                t2cost = prevBestCost; // left side just has cost of whatever
                                       // left
                // subtree is
                t2card = bestCard;
                rightPkey = hasPkey(prevBest);

                t1cost = stats.get(table1Name).estimateScanCost();
                t1card = stats.get(table1Name).estimateTableCardinality(
                        filterSelectivities.get(j.t1Alias));
                leftPkey = isPkey(j.t1Alias, j.f1PureName);

            } else {
                // don't consider this plan if one of j.t1 or j.t2
                // isn't a table joined in prevBest (cross product)
                return null;
            }
        }

        // case where prevbest is left
        double cost1 = estimateJoinCost(j, t1card, t2card, t1cost, t2cost);

        LogicalJoinNode j2 = j.swapInnerOuter();
        double cost2 = estimateJoinCost(j2, t2card, t1card, t2cost, t1cost);
        if (cost2 < cost1) {
            boolean tmp;
            j = j2;
            cost1 = cost2;
            tmp = rightPkey;
            rightPkey = leftPkey;
            leftPkey = tmp;
        }
        if (cost1 >= bestCostSoFar)
            return null;

        CostCard cc = new CostCard();

        cc.card = estimateJoinCardinality(j, t1card, t2card, leftPkey,
                rightPkey, stats);
        cc.cost = cost1;
        cc.plan = (Vector<LogicalJoinNode>) prevBest.clone();
        cc.plan.addElement(j); // prevbest is left -- add new join to end
        return cc;
    }

    /**
     * Return true if the specified table is in the list of joins, false
     * otherwise
     */
    private boolean doesJoin(Vector<LogicalJoinNode> joinlist, String table) {
        for (LogicalJoinNode j : joinlist) {
            if (j.t1Alias.equals(table)
                    || (j.t2Alias != null && j.t2Alias.equals(table)))
                return true;
        }
        return false;
    }

    /**
     * Return true if field is a primary key of the specified table, false
     * otherwise
     * 
     * @param tableAlias
     *            The alias of the table in the query
     * @param field
     *            The pure name of the field
     */
    private boolean isPkey(String tableAlias, String field) {
        int tid1 = p.getTableId(tableAlias);
        String pkey1 = Database.getCatalog().getPrimaryKey(tid1);

        return pkey1.equals(field);
    }

    /**
     * Return true if a primary key field is joined by one of the joins in
     * joinlist
     */
    private boolean hasPkey(Vector<LogicalJoinNode> joinlist) {
        for (LogicalJoinNode j : joinlist) {
            if (isPkey(j.t1Alias, j.f1PureName)
                    || (j.t2Alias != null && isPkey(j.t2Alias, j.f2PureName)))
                return true;
        }
        return false;

    }

    /**
     * Helper function to display a Swing window with a tree representation of
     * the specified list of joins. See {@link #orderJoins}, which may want to
     * call this when the analyze flag is true.
     * 
     * @param js
     *            the join plan to visualize
     * @param pc
     *            the PlanCache accumulated whild building the optimal plan
     * @param stats
     *            table statistics for base tables
     * @param selectivities
     *            the selectivities of the filters over each of the tables
     *            (where tables are indentified by their alias or name if no
     *            alias is given)
     */
    private void printJoins(Vector<LogicalJoinNode> js, PlanCache pc,
            HashMap<String, TableStats> stats,
            HashMap<String, Double> selectivities) {

        JFrame f = new JFrame("Join Plan for " + p.getQuery());

        // Set the default close operation for the window,
        // or else the program won't exit when clicking close button
        f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        f.setVisible(true);

        f.setSize(300, 500);

        HashMap<String, DefaultMutableTreeNode> m = new HashMap<String, DefaultMutableTreeNode>();

        // int numTabs = 0;

        // int k;
        DefaultMutableTreeNode root = null, treetop = null;
        HashSet<LogicalJoinNode> pathSoFar = new HashSet<LogicalJoinNode>();
        boolean neither;

        System.out.println(js);
        for (LogicalJoinNode j : js) {
            pathSoFar.add(j);
            System.out.println("PATH SO FAR = " + pathSoFar);

            String table1Name = Database.getCatalog().getTableName(
                    this.p.getTableId(j.t1Alias));
            String table2Name = Database.getCatalog().getTableName(
                    this.p.getTableId(j.t2Alias));

            // Double c = pc.getCost(pathSoFar);
            neither = true;

            root = new DefaultMutableTreeNode("Join " + j + " (Cost ="
                    + pc.getCost(pathSoFar) + ", card = "
                    + pc.getCard(pathSoFar) + ")");
            DefaultMutableTreeNode n = m.get(j.t1Alias);
            if (n == null) { // never seen this table before
                n = new DefaultMutableTreeNode(j.t1Alias
                        + " (Cost = "
                        + stats.get(table1Name).estimateScanCost()
                        + ", card = "
                        + stats.get(table1Name).estimateTableCardinality(
                                selectivities.get(j.t1Alias)) + ")");
                root.add(n);
            } else {
                // make left child root n
                root.add(n);
                neither = false;
            }
            m.put(j.t1Alias, root);

            n = m.get(j.t2Alias);
            if (n == null) { // never seen this table before

                n = new DefaultMutableTreeNode(
                        j.t2Alias == null ? "Subplan"
                                : (j.t2Alias
                                        + " (Cost = "
                                        + stats.get(table2Name)
                                                .estimateScanCost()
                                        + ", card = "
                                        + stats.get(table2Name)
                                                .estimateTableCardinality(
                                                        selectivities
                                                                .get(j.t2Alias)) + ")"));
                root.add(n);
            } else {
                // make right child root n
                root.add(n);
                neither = false;
            }
            m.put(j.t2Alias, root);

            // unless this table doesn't join with other tables,
            // all tables are accessed from root
            if (!neither) {
                for (String key : m.keySet()) {
                    m.put(key, root);
                }
            }

            treetop = root;
        }

        JTree tree = new JTree(treetop);
        JScrollPane treeView = new JScrollPane(tree);

        tree.setShowsRootHandles(true);

        // Set the icon for leaf nodes.
        ImageIcon leafIcon = new ImageIcon("join.jpg");
        DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
        renderer.setOpenIcon(leafIcon);
        renderer.setClosedIcon(leafIcon);

        tree.setCellRenderer(renderer);

        f.setSize(300, 500);

        f.add(treeView);
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }

        if (js.size() == 0) {
            f.add(new JLabel("No joins in plan."));
        }

        f.pack();

    }

}
