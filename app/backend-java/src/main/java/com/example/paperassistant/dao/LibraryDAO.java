package com.example.paperassistant.dao;

import com.example.paperassistant.model.dataobject.LibraryDO;
import java.util.List;
import java.util.Optional;

/** 论文库数据访问接口：只负责持久化，不处理 HTTP 或业务规则。 */
public interface LibraryDAO {

    /**
     * 插入论文库，只使用输入的名称和描述。
     *
     * @param library 待插入记录；ID 和创建、修改时间可为空，由数据库生成
     * @return 包含数据库生成字段的完整记录
     */
    LibraryDO insertLibrary(LibraryDO library);

    /**
     * 查询论文库，按 gmt_create DESC、id DESC 排序。
     *
     * @return 持久化记录列表，无数据时返回空列表
     */
    List<LibraryDO> listLibraries();

    /**
     * 按 ID 更新名称和描述，修改时间由数据库设置，创建时间不变。
     *
     * @param library 需包含 ID、名称和描述，其余输入字段忽略
     * @return 更新后的记录；ID 不存在时返回 Optional.empty()
     */
    Optional<LibraryDO> updateLibrary(LibraryDO library);

    /**
     * 按 ID 物理删除一条记录。
     *
     * @param id 论文库 ID
     * @return 受影响行数：1 表示已删除，0 表示不存在
     */
    int deleteLibrary(long id);
}
