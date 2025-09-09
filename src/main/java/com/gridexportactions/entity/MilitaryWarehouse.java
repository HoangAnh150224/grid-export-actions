package com.gridexportactions.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

@JmixEntity
@Table(name = "MILITARY_WAREHOUSE")
@Entity
public class MilitaryWarehouse {
    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Column(name = "LOAI")
    private String loai;

    @Column(name = "TEN")
    private String ten;

    @Column(name = "TRANG_THAI")
    private String trangThai;

    @Column(name = "NHA_SAN_XUAT")
    private String nhaSanXuat;

    @Column(name = "SO_SERI")
    private String soSeri;

    @Column(name = "SO_LUONG")
    private Integer soLuong;

    @Column(name = "NGAY_TIEP_NHAN")
    private LocalDate ngayTiepNhan;

    @Column(name = "HAN_SU_DUNG_BAO_DUONG")
    private LocalDate hanSuDungBaoDuong;

    @Column(name = "CO_NONG")
    private String coNong;

    @Column(name = "GHI_CHU")
    private String ghiChu;

    @Column(name = "VERSION", nullable = false)
    @Version
    private Integer version;

    public String getCoNong() {
        return coNong;
    }

    public void setCoNong(String coNong) {
        this.coNong = coNong;
    }

    public LocalDate getHanSuDungBaoDuong() {
        return hanSuDungBaoDuong;
    }

    public void setHanSuDungBaoDuong(LocalDate hanSuDungBaoDuong) {
        this.hanSuDungBaoDuong = hanSuDungBaoDuong;
    }

    public LocalDate getNgayTiepNhan() {
        return ngayTiepNhan;
    }

    public void setNgayTiepNhan(LocalDate ngayTiepNhan) {
        this.ngayTiepNhan = ngayTiepNhan;
    }

    public String getGhiChu() {
        return ghiChu;
    }

    public void setGhiChu(String ghiChu) {
        this.ghiChu = ghiChu;
    }

    public Integer getSoLuong() {
        return soLuong;
    }

    public void setSoLuong(Integer soLuong) {
        this.soLuong = soLuong;
    }

    public String getSoSeri() {
        return soSeri;
    }

    public void setSoSeri(String soSeri) {
        this.soSeri = soSeri;
    }

    public String getNhaSanXuat() {
        return nhaSanXuat;
    }

    public void setNhaSanXuat(String nhaSanXuat) {
        this.nhaSanXuat = nhaSanXuat;
    }

    public String getTen() {
        return ten;
    }

    public void setTen(String ten) {
        this.ten = ten;
    }

    public String getTrangThai() {
        return trangThai;
    }

    public void setTrangThai(String trangThai) {
        this.trangThai = trangThai;
    }

    public String getLoai() {
        return loai;
    }

    public void setLoai(String loai) {
        this.loai = loai;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

}