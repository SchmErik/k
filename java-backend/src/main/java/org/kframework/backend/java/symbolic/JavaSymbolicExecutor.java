// Copyright (c) 2013-2014 K Team. All Rights Reserved.
package org.kframework.backend.java.symbolic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.kframework.backend.java.kil.ConstrainedTerm;
import org.kframework.backend.java.kil.Definition;
import org.kframework.backend.java.kil.GlobalContext;
import org.kframework.backend.java.kil.KilFactory;
import org.kframework.backend.java.kil.Rule;
import org.kframework.backend.java.kil.Term;
import org.kframework.backend.java.kil.TermContext;
import org.kframework.backend.java.kil.Variable;
import org.kframework.backend.unparser.OutputModes;
import org.kframework.backend.unparser.UnparserFilter;
import org.kframework.compile.utils.RuleCompilerSteps;
import org.kframework.kil.loader.Context;
import org.kframework.krun.ColorSetting;
import org.kframework.krun.KRunExecutionException;
import org.kframework.krun.SubstitutionFilter;
import org.kframework.krun.api.KRunResult;
import org.kframework.krun.api.KRunState;
import org.kframework.krun.api.SearchResult;
import org.kframework.krun.api.SearchResults;
import org.kframework.krun.api.SearchType;
import org.kframework.krun.tools.Executor;
import com.google.inject.Inject;
import com.google.inject.Provider;

public class JavaSymbolicExecutor implements Executor {

    private final Definition definition;
    private final JavaExecutionOptions javaOptions;
    private final KilFactory kilFactory;
    private final GlobalContext globalContext;
    private final Provider<SymbolicRewriter> symbolicRewriter;
    private final KILtoBackendJavaKILTransformer transformer;
    private final Context context;

    @Inject
    JavaSymbolicExecutor(
            Context context,
            JavaExecutionOptions javaOptions,
            KilFactory kilFactory,
            GlobalContext globalContext,
            Provider<SymbolicRewriter> symbolicRewriter,
            KILtoBackendJavaKILTransformer transformer,
            Definition definition) {
        this.context = context;
        this.javaOptions = javaOptions;
        this.kilFactory = kilFactory;
        this.globalContext = globalContext;
        this.symbolicRewriter = symbolicRewriter;
        this.transformer = transformer;
        this.definition = definition;
        globalContext.setDefinition(definition);
    }

    @Override
    public KRunResult<KRunState> run(org.kframework.kil.Term cfg) throws KRunExecutionException {
        return internalRun(cfg, -1);
    }

    private KRunResult<KRunState> internalRun(org.kframework.kil.Term cfg, int bound) throws KRunExecutionException {
        ConstrainedTerm result = javaKILRun(cfg, bound);
        org.kframework.kil.Term kilTerm = (org.kframework.kil.Term) result.term().accept(
                new BackendJavaKILtoKILTransformer(context));
        KRunResult<KRunState> returnResult = new KRunResult<KRunState>(new KRunState(kilTerm));
        UnparserFilter unparser = new UnparserFilter(true, ColorSetting.OFF, OutputModes.PRETTY, context);
        unparser.visitNode(kilTerm);
        returnResult.setRawOutput(unparser.getResult());
        return returnResult;
    }

    private ConstrainedTerm javaKILRun(org.kframework.kil.Term cfg, int bound) {
        Term term = kilFactory.term(cfg);
        TermContext termContext = TermContext.of(globalContext);
        term = term.evaluate(termContext);

        if (javaOptions.patternMatching) {
            FastDestructiveRewriter rewriter = new FastDestructiveRewriter(definition, termContext);
            ConstrainedTerm rewriteResult = new ConstrainedTerm(rewriter.rewrite(term, bound), termContext);
            return rewriteResult;
        } else {
            SymbolicConstraint constraint = new SymbolicConstraint(termContext);
            ConstrainedTerm constrainedTerm = new ConstrainedTerm(term, constraint, termContext);
            return getSymbolicRewriter().rewrite(constrainedTerm, bound);
        }
    }


    @Override
    public KRunResult<SearchResults> search(
            Integer bound,
            Integer depth,
            SearchType searchType,
            org.kframework.kil.Rule pattern,
            org.kframework.kil.Term cfg,
            RuleCompilerSteps compilationInfo) throws KRunExecutionException {

        List<Rule> claims = Collections.emptyList();
        if (bound == null) {
            bound = -1;
        }
        if (depth == null) {
            depth = -1;
        }

        // The pattern needs to be a rewrite in order for the transformer to be
        // able to handle it, so we need to give it a right-hand-side.
        org.kframework.kil.Cell c = new org.kframework.kil.Cell();
        c.setLabel("generatedTop");
        c.setContents(new org.kframework.kil.Bag());
        pattern.setBody(new org.kframework.kil.Rewrite(pattern.getBody(), c, context));
        Rule patternRule = transformer.transformRule(pattern);

        List<SearchResult> searchResults = new ArrayList<SearchResult>();
        List<Map<Variable,Term>> hits;
        if (javaOptions.patternMatching) {
            Term initialTerm = kilFactory.term(cfg);
            Term targetTerm = null;
            GroundRewriter rewriter = new GroundRewriter(definition, TermContext.of(globalContext));
            hits = rewriter.search(initialTerm, targetTerm, claims,
                    patternRule, bound, depth, searchType);
        } else {
            ConstrainedTerm initialTerm = new ConstrainedTerm(kilFactory.term(cfg), TermContext.of(globalContext));
            ConstrainedTerm targetTerm = null;
            hits = getSymbolicRewriter().search(initialTerm, targetTerm, claims,
                    patternRule, bound, depth, searchType);
        }

        for (Map<Variable,Term> map : hits) {
            // Construct substitution map from the search results
            Map<String, org.kframework.kil.Term> substitutionMap =
                    new HashMap<String, org.kframework.kil.Term>();
            for (Variable var : map.keySet()) {
                org.kframework.kil.Term kilTerm =
                        (org.kframework.kil.Term) map.get(var).accept(
                                new BackendJavaKILtoKILTransformer(context));
                substitutionMap.put(var.name(), kilTerm);
            }

            // Apply the substitution to the pattern
            org.kframework.kil.Term rawResult =
                    (org.kframework.kil.Term) new SubstitutionFilter(substitutionMap, context)
                        .visitNode(pattern.getBody());

            searchResults.add(new SearchResult(
                    new KRunState(rawResult),
                    substitutionMap,
                    compilationInfo,
                    context));
        }

        // TODO(ericmikida): Make the isDefaultPattern option set in some reasonable way
        KRunResult<SearchResults> searchResultsKRunResult = new KRunResult<>(new SearchResults(
                searchResults,
                null,
                false));

        return searchResultsKRunResult;
    }

    @Override
    public KRunResult<KRunState> step(org.kframework.kil.Term cfg, int steps)
            throws KRunExecutionException {
        return internalRun(cfg, steps);
    }

    public SymbolicRewriter getSymbolicRewriter() {
        return symbolicRewriter.get();
    }
}
