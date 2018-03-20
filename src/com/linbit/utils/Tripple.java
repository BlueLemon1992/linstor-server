package com.linbit.utils;

import com.google.common.base.Objects;

public class Tripple<A, B, C> implements Comparable<Tripple<A, B, C>>
{
    public A objA;
    public B objB;
    public C objC;

    public Tripple()
    {
    }

    public Tripple(A aRef, B bRef, C cRef)
    {
        objA = aRef;
        objB = bRef;
        objC = cRef;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((objA == null) ? 0 : objA.hashCode());
        result = prime * result + ((objB == null) ? 0 : objB.hashCode());
        result = prime * result + ((objC == null) ? 0 : objC.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        boolean eq = this == obj;
        if (!eq && obj != null && getClass() == obj.getClass())
        {
            Tripple<?, ?, ?> other = (Tripple<?, ?, ?>) obj;
            eq = Objects.equal(objA, other.objA) &&
                Objects.equal(objB, other.objB) &&
                Objects.equal(objC, other.objC);
        }
        return eq;
    }

    @SuppressWarnings("unchecked")
    @Override
    public int compareTo(Tripple<A, B, C> other)
    {
        int eq = 0;
        if (objA instanceof Comparable)
        {
            eq = ((Comparable<A>) objA).compareTo(other.objA);
        }
        if (eq == 0 && objB instanceof Comparable)
        {
            eq = ((Comparable<B>) objB).compareTo(other.objB);
        }
        if (eq == 0 && objC instanceof Comparable)
        {
            eq = ((Comparable<C>) objC).compareTo(other.objC);
        }
        return eq;
    }
}
