/*
 *  Copyright 2016 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.flavour.expr.type.inference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.teavm.flavour.expr.type.TypeVar;

/**
 *
 * @author Alexey Andreev
 */
public class Variable {
    private static int idGenerator;
    int id = idGenerator++;
    private TypeConstraints constraints;
    private List<Variable> upperDependencies = new ArrayList<>();
    private List<ClassType> upperBounds = new ArrayList<>();
    private List<ClassType> lowerBounds = new ArrayList<>();
    private Type instance;
    int rank;
    private Variable parent;
    TypeVar impl;

    Variable(TypeConstraints constraints, TypeVar impl) {
        this.constraints = constraints;
        this.impl = impl;
    }

    private Variable union(Variable other) {
        if (rank < other.rank) {
            other.parent = this;
            return this;
        } else if (rank > other.rank) {
            parent = other;
            return other;
        } else {
            other.parent = this;
            ++rank;
            return this;
        }
    }

    /**
     * Returns main representative of set equivalent types T1 = T2 = ... = Tn. Which representative is returned
     * is undefined, but it is guaranteed that it will be always the same representative for the given set.
     */
    public Variable find() {
        Variable var = this;
        if (var.parent != null) {
            if (var.parent.parent == null) {
                return var.parent;
            }
            List<Variable> path = new ArrayList<>();
            do {
                path.add(var);
                var = var.parent;
            } while (var.parent != null);
            for (Variable pathVar : path) {
                pathVar.parent = var;
            }
        }
        return var;
    }

    /**
     * Checks whether this = other has been proven
     */
    public boolean isSameWith(Variable other) {
        return find() == other.find();
    }

    /**
     * Checks whether this <: other has been proven
     */
    public boolean isSubtypeOf(Variable other) {
        Variable s = other.find();
        if (this == s) {
            return true;
        }
        return find().upperDependencies.stream().anyMatch(v -> v.find() == s);
    }

    /**
     * Introduces this <: other constraint if possible. Returns true if success or false if contradictory
     * constraint has been found.
     */
    public boolean assertEqualTo(Variable other) {
        Variable first = this.find();
        Variable second = other.find();
        if (first == second) {
            return true;
        }
        Variable joined = first.union(second);
        other = joined == first ? second : first;
        return assertEqualToImpl(joined, other);
    }

    private static boolean assertEqualToImpl(Variable s, Variable t) {
        if (s == t) {
            return true;
        }
        boolean ok = true;

        if (s.instance != null && t.instance != null) {
            ok &= adoptInstance(s, t);
            ok &= s.constraints.assertEqual(s.instance, t.instance);
        } else if (s.instance != null) {
            ok &= adoptInstance(t, s);
        } else if (t.instance != null) {
            ok &= adoptInstance(s, t);
        }

        return ok;
    }

    private static boolean adoptInstance(Variable s, Variable t) {
        boolean ok = true;
        s.instance = t.instance;

        Set<Variable> newUpperDependencies = findMany(t.upperDependencies);
        newUpperDependencies.removeAll(findMany(s.upperDependencies));
        List<ClassType> newUpperBounds = new ArrayList<>(t.upperBounds);
        List<ClassType> newLowerBounds = new ArrayList<>(t.lowerBounds);

        // When T <: S1 <: S2 <: ... <: Sn <: T then T = S1 = S2 = ... = Sn
        Set<Variable> circularDependencies = newUpperDependencies.stream().filter(u -> u.isSubtypeOf(s))
                .collect(Collectors.toSet());
        newUpperDependencies.removeAll(circularDependencies);
        for (Variable u : circularDependencies) {
            ok &= s.assertEqualTo(u);
        }

        for (Variable u : newUpperDependencies) {
            ok &= s.assertSubtypeOf(u);
        }
        s.assertSubtypeOf(newUpperBounds);
        s.assertSupertypeOf(newLowerBounds);

        return ok;
    }

    /**
     * Introduces this = cls constraint if possible. Returns true if success or false if contradictory
     * constraint has been found.
     */
    public boolean assertEqualTo(ClassType cls) {
        return assertEqualToImpl(find(), cls);
    }

    private static boolean assertEqualToImpl(Variable s, ClassType t) {
        boolean ok = true;
        if (s.instance == null) {
            s.instance = t;
            s.constraints.assertSubtype(t, s.upperBounds);
            s.constraints.assertSubtype(s.lowerBounds, t);
            for (Variable dep : s.upperDependencies.toArray(new Variable[0])) {
                dep.assertSupertypeOf(Arrays.asList(t));
            }
        } else {
            ok &= s.constraints.assertEqual(s.instance, t);
        }
        return ok;
    }

    /**
     * Introduces this <: other constraint if possible. Returns true if success or false if contradictory
     * constraint has been found.
     */
    public boolean assertSubtypeOf(Variable other) {
        if (isSubtypeOf(other)) {
            return true;
        }

        if (other.isSubtypeOf(this)) {
            return assertEqualTo(other);
        }

        boolean ok = true;
        Set<Variable> newUpperDependencies = findMany(other.upperDependencies);
        List<ClassType> newUpperBounds = new ArrayList<>(other.upperBounds);
        List<ClassType> newLowerBounds = new ArrayList<>(lowerBounds);
        newUpperDependencies.removeAll(findMany(upperDependencies));

        Set<Variable> circularDependencies = newUpperDependencies.stream().filter(u -> u.isSubtypeOf(this))
                .collect(Collectors.toSet());
        newUpperDependencies.removeAll(circularDependencies);
        for (Variable u : circularDependencies) {
            ok &= assertEqualTo(u);
        }

        if (!isSameWith(other)) {
            assertSubtypeOf(newUpperBounds);
            assertSupertypeOf(newLowerBounds);
        }

        return ok;
    }

    public boolean assertSubtypeOf(List<ClassType> supertype) {
        return assertSubtypeOfImpl(this.find(), supertype);
    }

    private static boolean assertSubtypeOfImpl(Variable s, List<ClassType> t) {
        if (s.upperBounds.contains(t)) {
            return true;
        }

        TypeConstraints constraints = s.constraints;
        List<ClassType> typesToEliminate = s.upperBounds.stream()
                .filter(u -> t.stream().anyMatch(v -> constraints.isSubclass(v.getName(), u.getName())))
                .collect(Collectors.toList());
        s.upperBounds.addAll(t);
        boolean ok = true;
        if (!typesToEliminate.isEmpty()) {
            s.upperBounds.removeAll(typesToEliminate);
            for (ClassType eliminatedType : typesToEliminate) {
                ok &= constraints.assertSubtype(t, eliminatedType);
            }
        }

        if (s.instance != null) {
            ok &= constraints.assertSubtype(s.instance, t);
        }
        for (ClassType lowerBound : s.lowerBounds) {
            ok &= constraints.assertSubtype(lowerBound, t);
        }

        return ok;
    }

    public boolean assertSupertypeOf(List<ClassType> supertype) {
        return assertSupertypeOfImpl(this.find(), supertype);
    }

    private static boolean assertSupertypeOfImpl(Variable s, List<ClassType> t) {
        if (s.lowerBounds.contains(t)) {
            return true;
        }

        TypeConstraints constraints = s.constraints;
        Set<String> lowerBoundsErasure = s.lowerBounds.stream().map(ClassType::getName).collect(Collectors.toSet());
        Set<String> newErasure = t.stream().map(ClassType::getName).collect(Collectors.toSet());
        Set<String> commonSupertypes = constraints.commonSupertypes(lowerBoundsErasure, newErasure);

        List<ClassType> oldTypes = new ArrayList<>(s.lowerBounds);
        oldTypes.addAll(t);

        boolean ok = true;
        s.lowerBounds.clear();
        s.lowerBounds.addAll(commonSupertypes.stream()
                .map(constraints::genericClass)
                .collect(Collectors.toList()));
        List<ClassType> lowerBounds = new ArrayList<>(s.lowerBounds);
        for (ClassType oldType : oldTypes) {
            for (ClassType newType : lowerBounds.toArray(new ClassType[0])) {
                List<ClassType> path = constraints.sublassPath(oldType, newType);
                if (path != null) {
                    ClassType last = path.get(path.size() - 1);
                    ok &= constraints.assertEqual(newType, last);
                }
            }
        }

        if (s.instance != null) {
            ok &= constraints.assertSubtype(t, s.instance);
        }
        for (ClassType lowerBound : s.lowerBounds) {
            ok &= constraints.assertSubtype(t, lowerBound);
        }

        return ok;
    }

    static Set<Variable> findMany(Collection<Variable> vars) {
        return vars.stream().map(Variable::find).collect(Collectors.toSet());
    }

    public Reference createReference() {
        return new Reference(this);
    }

    public static class Reference extends Type {
        private Variable variable;

        Reference(Variable variable) {
            this.variable = variable;
        }

        public Variable getVariable() {
            variable = variable.find();
            return variable;
        }

        @Override
        public String toString() {
            return String.valueOf(variable.id);
        }
    }

    @Override
    public String toString() {
        return super.toString();
    }
}