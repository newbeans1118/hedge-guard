package com.hedgeguard.testsupport;

import com.hedgeguard.dao.BaseDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;

import java.util.List;

/**
 * 假 DAO 基类：{@link BaseDao} 的五个通用方法一律<b>显式失败</b>。
 *
 * <p>用途：服务层单测只需实现<b>被用例真正驱动</b>的方法；未实现者一旦被调用即失败，
 * 避免"桩静默返回 {@code null}/{@code 0}"把缺陷掩盖成通过。
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public abstract class UnsupportedBaseDao<T, ID> implements BaseDao<T, ID> {

    @Override
    public int insert(T entity, long operatorId) {
        throw FakeDao.unsupported(prefix() + ".insert");
    }

    @Override
    public int updateById(T entity, long operatorId) {
        throw FakeDao.unsupported(prefix() + ".updateById");
    }

    @Override
    public T findById(ID id) {
        throw FakeDao.unsupported(prefix() + ".findById");
    }

    @Override
    public PageResult<T> findPage(PageQuery query, String where, List<Object> whereArgs) {
        throw FakeDao.unsupported(prefix() + ".findPage");
    }

    @Override
    public long count(String where, List<Object> whereArgs) {
        throw FakeDao.unsupported(prefix() + ".count");
    }

    private String prefix() {
        return getClass().getSimpleName();
    }
}
